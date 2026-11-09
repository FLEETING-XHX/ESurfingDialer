#include "esurfing/module_loader.hpp"
#include <algorithm>
#include <array>
#include <fstream>
#include <limits>
#include <map>
#include <memory>
#include <new>
#include <string_view>
#include <utility>
#include <lzma.h>
#include <openssl/sha.h>

extern "C" int es_zuc_transform(const std::uint8_t*, const std::uint8_t*,
                                const std::uint8_t*, std::size_t, std::uint8_t*);

namespace esurfing {
namespace {
using Bytes = std::span<const std::uint8_t>;
constexpr std::size_t max_input = 1024 * 1024;
constexpr std::uint32_t max_dictionary = 16 * 1024 * 1024;
constexpr std::string_view native_sha = "69948f5005c308166f7de48a0520edde792ad786ed3bf52d6d0d674d6b055a3c";
constexpr std::string_view masked_sha = "5d0d38e512f0c25f8f6423d2f66754f28e2af78cc9826b7776f2eff5d5a7e7d7";
constexpr std::string_view sm4_masked_sha = "c75879a06b55fe814cb280cf911d59dd612779463778e32e9bad031a70c1a545";

void require(bool condition, const char* code) { if (!condition) throw ModuleError(code); }
Bytes range(Bytes bytes, std::size_t offset, std::size_t count) {
    require(offset <= bytes.size() && count <= bytes.size() - offset, "ELF_RANGE_REJECTED");
    return bytes.subspan(offset, count);
}
std::uint16_t u16(Bytes bytes, std::size_t offset) {
    auto value = range(bytes, offset, 2);
    return static_cast<std::uint16_t>(value[0] | (std::uint16_t(value[1]) << 8));
}
std::uint32_t u32(Bytes bytes, std::size_t offset) {
    auto value = range(bytes, offset, 4);
    return std::uint32_t(value[0]) | (std::uint32_t(value[1]) << 8) |
           (std::uint32_t(value[2]) << 16) | (std::uint32_t(value[3]) << 24);
}
std::string sha256(Bytes bytes) {
    std::array<unsigned char, SHA256_DIGEST_LENGTH> digest{};
    require(SHA256(bytes.data(), bytes.size(), digest.data()) != nullptr, "MODULE_DIGEST_FAILED");
    constexpr char hex[] = "0123456789abcdef";
    std::string result;
    result.reserve(digest.size() * 2);
    for (auto value : digest) { result += hex[value >> 4]; result += hex[value & 15]; }
    return result;
}

struct Segment { std::uint32_t address, offset, size, flags; };
struct Section { std::uint32_t type, address, offset, size; };
class Elf32 {
    Bytes bytes_;
    std::vector<Segment> segments_;
    std::map<std::string, Section, std::less<>> sections_;
public:
    explicit Elf32(Bytes bytes) : bytes_(bytes) {
        require(bytes.size() >= 52 && bytes.size() <= max_input, "ELF_SIZE_REJECTED");
        constexpr std::array<std::uint8_t, 7> identity{0x7f, 'E', 'L', 'F', 1, 1, 1};
        require(std::equal(identity.begin(), identity.end(), bytes.begin()), "ELF_CLASS_REJECTED");
        require(u16(bytes, 16) == 3 && u16(bytes, 18) == 40 && u32(bytes, 20) == 1 &&
                u16(bytes, 40) == 52, "ELF_ABI_REJECTED");
        auto phoff = u32(bytes, 28), shoff = u32(bytes, 32);
        auto phsize = u16(bytes, 42), phcount = u16(bytes, 44);
        auto shsize = u16(bytes, 46), shcount = u16(bytes, 48), names_index = u16(bytes, 50);
        require(phsize == 32 && phcount > 0 && phcount <= 64, "ELF_PROGRAM_HEADERS_REJECTED");
        require(shsize == 40 && shcount > 0 && shcount <= 256 && names_index < shcount,
                "ELF_SECTIONS_REJECTED");
        range(bytes, phoff, std::size_t(phcount) * 32);
        range(bytes, shoff, std::size_t(shcount) * 40);
        for (std::size_t i = 0; i < phcount; ++i) {
            auto ph = range(bytes, std::size_t(phoff) + i * 32, 32);
            auto size = u32(ph, 16), memory = u32(ph, 20), address = u32(ph, 8);
            require(size <= memory && memory <= std::numeric_limits<std::uint32_t>::max() - address,
                    "ELF_SEGMENT_SIZE_REJECTED");
            if (u32(ph, 0) == 1) {
                auto offset = u32(ph, 4);
                range(bytes, offset, size);
                segments_.push_back({address, offset, size, u32(ph, 24)});
            }
        }
        auto names_header = range(bytes, std::size_t(shoff) + std::size_t(names_index) * 40, 40);
        auto names = range(bytes, u32(names_header, 16), u32(names_header, 20));
        for (std::size_t i = 0; i < shcount; ++i) {
            auto sh = range(bytes, std::size_t(shoff) + i * 40, 40);
            auto index = u32(sh, 0);
            require(index < names.size(), "ELF_SECTION_NAME_REJECTED");
            auto limit = std::min(names.size() - index, std::size_t(129));
            auto name_bytes = names.subspan(index, limit);
            auto end = std::find(name_bytes.begin(), name_bytes.end(), std::uint8_t(0));
            require(end != name_bytes.end(), "ELF_SECTION_NAME_REJECTED");
            if (end == name_bytes.begin()) continue;
            require(std::all_of(name_bytes.begin(), end, [](auto c) { return c >= 0x20 && c < 0x7f; }),
                    "ELF_SECTION_NAME_REJECTED");
            std::string name(name_bytes.begin(), end);
            auto inserted = sections_.emplace(std::move(name),
                Section{u32(sh, 4), u32(sh, 12), u32(sh, 16), u32(sh, 20)});
            require(inserted.second, "ELF_DUPLICATE_SECTION");
        }
    }
    std::size_t file_offset(std::uint32_t address, std::size_t count, bool writable = false) const {
        std::size_t result = 0, matches = 0;
        for (const auto& segment : segments_) {
            if (address < segment.address) continue;
            auto delta = address - segment.address;
            if (delta > segment.size || count > segment.size - delta) continue;
            if (writable)
                require((segment.flags & 6) == 6 && !(segment.flags & 1), "POOL_SEGMENT_REJECTED");
            result = std::size_t(segment.offset) + delta;
            ++matches;
        }
        require(matches == 1, "ELF_VIRTUAL_RANGE_REJECTED");
        range(bytes_, result, count);
        return result;
    }
    Bytes at(std::uint32_t address, std::size_t count, bool writable = false) const {
        return range(bytes_, file_offset(address, count, writable), count);
    }
    const Section& section(std::string_view name) const {
        auto found = sections_.find(name);
        require(found != sections_.end(), "MODULE_CODE_PROFILE_UNKNOWN");
        return found->second;
    }
    void verify_section(std::string_view name, std::uint32_t address, std::size_t size,
                        std::string_view expected_sha) const {
        const auto& value = section(name);
        require(value.type != 8 && value.address == address && value.size == size,
                "MODULE_CODE_PROFILE_UNKNOWN");
        require(sha256(range(bytes_, value.offset, value.size)) == expected_sha,
                "MODULE_CODE_PROFILE_UNKNOWN");
    }
    std::uint32_t pc_relative(std::uint32_t instruction) const {
        auto code = at(instruction, 4);
        auto load = u16(code, 0), add = u16(code, 2);
        require((load & 0xf800) == 0x4800, "PROFILE_PC_LOAD_REJECTED");
        require((add & 0xff00) == 0x4400 && ((add >> 3) & 15) == 15, "PROFILE_PC_ADD_REJECTED");
        auto target_reg = (load >> 8) & 7;
        auto destination = (add & 7) | ((add >> 4) & 8);
        require(target_reg == destination, "PROFILE_PC_REGISTER_REJECTED");
        auto literal = ((instruction + 4) & ~std::uint32_t(3)) + (load & 255) * 4;
        return instruction + 6 + u32(at(literal, 4), 0);
    }
};

struct Header {
    std::string algorithm_id;
    std::size_t payload_offset;
    std::uint32_t target_size, dictionary;
    unsigned lc, lp, pb;
    unsigned encoding;
};
Header parse_header(Bytes module) {
    require(module.size() >= 5 && module.size() <= max_input &&
            module[0] == '0' && module[1] == '0' && module[2] == '1', "MODULE_FORMAT_UNKNOWN");
    std::size_t position = 3;
    Bytes id;
    for (unsigned field = 0; field < 2; ++field) {
        require(position < module.size(), "MODULE_HEADER_TRUNCATED");
        auto count = module[position++];
        require(count <= module.size() - position, "MODULE_HEADER_TRUNCATED");
        if (field == 1) id = module.subspan(position, count);
        position += count;
    }
    require(id.size() == 36, "MODULE_ID_INVALID");
    std::string name;
    name.reserve(36);
    for (std::size_t i = 0; i < id.size(); ++i) {
        auto c = id[i];
        if (i == 8 || i == 13 || i == 18 || i == 23) require(c == '-', "MODULE_ID_INVALID");
        else require((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'),
                     "MODULE_ID_INVALID");
        if (c >= 'a' && c <= 'f') c = std::uint8_t(c - 'a' + 'A');
        name += char(c);
    }
    require(module.size() - position > 9, "MODULE_BODY_TRUNCATED");
    auto descriptor = u32(module, position + 5);
    auto encoding=descriptor>>28;
    require(encoding==0||encoding==3, "MODULE_ENCODING_PROFILE_UNKNOWN");
    auto expected = descriptor & 0x0fffffff;
    require(expected >= 52 && expected <= max_input, "MODULE_OUTPUT_SIZE_REJECTED");
    if(encoding==3)require((module.size() - position - 9) % 4 == 0, "MODULE_STREAM_ALIGNMENT_REJECTED");
    else require(module.size()-position-9>=5,"MODULE_STREAM_TOO_SHORT");
    unsigned prop = module[position], lc = prop % 9;
    prop /= 9;
    unsigned lp = prop % 5, pb = prop / 5;
    auto dictionary = u32(module, position + 1);
    require(pb <= 4 && lc + lp <= 4 && dictionary >= 4096 && dictionary <= max_dictionary,
            "MODULE_LZMA_PROPERTIES_REJECTED");
    return {std::move(name), position + 9, expected, dictionary, lc, lp, pb, encoding};
}

std::vector<std::uint8_t> unpack(Bytes module, Bytes library, const Header& header) {
    Elf32 native(library);
    std::array<std::uint8_t, 16> key{}, iv{};
    constexpr std::array<std::uint32_t, 4> key_literals{0x4808, 0x47fc, 0x47f0, 0x47f8};
    constexpr std::array<std::uint32_t, 4> iv_literals{0x47f4, 0x4800, 0x4804, 0x47ec};
    for (std::size_t i = 0; i < 4; ++i) {
        auto k = native.at(key_literals[i], 4), v = native.at(iv_literals[i], 4);
        std::copy(k.begin(), k.end(), key.begin() + i * 4);
        std::copy(v.begin(), v.end(), iv.begin() + i * 4);
    }
    auto encoded = module.subspan(header.payload_offset);
    std::vector<std::uint8_t> decoded(encoded.size());
    if(header.encoding==0){
        std::copy(encoded.begin(),encoded.end(),decoded.begin());
        for(std::size_t i=decoded.size()-1;i>0;--i)decoded[i-1]^=decoded[i];
    }else{
    require(es_zuc_transform(key.data(), iv.data(), encoded.data(), encoded.size(), decoded.data()) == 1,
            "MODULE_ZUC_FAILED");
    auto padding = decoded.back();
    // 原容器只使用末计数字节；前部填充不固定，不假设PKCS7。
    require(padding >= 1 && padding <= 4 && padding < decoded.size(), "MODULE_PADDING_REJECTED");
    decoded.resize(decoded.size() - padding);
    }
    std::vector<std::uint8_t> result(header.target_size);
    lzma_options_lzma options{};
    require(!lzma_lzma_preset(&options, 6), "MODULE_LZMA_SETUP_FAILED");
    options.dict_size = header.dictionary;
    options.lc = header.lc; options.lp = header.lp; options.pb = header.pb;
    lzma_filter filters[2]{{LZMA_FILTER_LZMA1, &options}, {LZMA_VLI_UNKNOWN, nullptr}};
    lzma_stream stream = LZMA_STREAM_INIT;
    struct EndStream { lzma_stream* stream; ~EndStream() { lzma_end(stream); } } end{&stream};
    auto status = lzma_raw_decoder(&stream, filters);
    require(status == LZMA_OK, "MODULE_LZMA_SETUP_FAILED");
    stream.next_in = decoded.data(); stream.avail_in = decoded.size();
    stream.next_out = result.data(); stream.avail_out = result.size();
    while (stream.avail_out > 0) {
        auto before_in = stream.avail_in, before_out = stream.avail_out;
        status = lzma_code(&stream, LZMA_RUN);
        require(status == LZMA_OK || status == LZMA_STREAM_END, "MODULE_LZMA_DECODE_FAILED");
        if (status == LZMA_STREAM_END ||
            (before_in == stream.avail_in && before_out == stream.avail_out)) break;
    }
    // 与native FINISH_ANY一致：填满声明的目标长度即停止，不强迫无EOS流继续读取。
    require(stream.avail_out == 0, "MODULE_UNPACKED_LENGTH_MISMATCH");
    return result;
}

ModuleMaterial inspect(Bytes elf_bytes, std::string algorithm_id) {
    Elf32 elf(elf_bytes);
    const auto& text=elf.section(".text");
    const bool sm4=text.address==0x1c40&&text.size==24896;
    if(sm4){
        elf.verify_section(".text",0x1c40,24896,"b1a8487cc9cb110e467ac00fe2c58b368dae385fdf31060b3e00e2f7f8912080");
        elf.verify_section(".rodata",0x8018,1110,"ab5c56e00c818c27c3717a76da4612918a1f26896ad56867500504e63e00090d");
        elf.verify_section(".plt",0x1830,1040,"1340462ea50148d2d7d17a41a2ec680222987543bc4c5099cdbfaeadd8afdcdc");
    }else{
    elf.verify_section(".text", 0x1d1c, 25688,
        "6d5e0725f87e5e14dc5270b50096b9e684dec50c3f8dc2e6c0001286db50e865");
    elf.verify_section(".rodata", 0x8434, 686,
        "b0a25256ae24882f076c66547bf2e25395bf1212879b948873f1f40585e5d6d7");
    elf.verify_section(".plt", 0x18dc, 1088,
        "ad7487816e4a7c256ca888ff1a8cc8e0124aafdec958e9d013e187fb4f0adf91");
    }
    auto pool_address = elf.pc_relative(sm4?0x38c4:0x31e4);
    const auto references=sm4?std::array<std::uint32_t,3>{0x38e6,0x39d0,0x3a04}:std::array<std::uint32_t,3>{0x3206,0x32f0,0x3324};
    for (auto instruction : references)
        require(elf.pc_relative(instruction) == pool_address, "POOL_REFERENCES_DISAGREE");
    auto pool = elf.at(pool_address, 256, true);
    std::size_t skip = pool[0xfe], key_size = pool[0xfb], iv_size = pool[0xf9];
    require(key_size==(sm4?16u:48u)&&iv_size==(sm4?16u:8u), "PARAMETER_LAYOUT_UNKNOWN");
    require(skip + key_size + iv_size + 7 <= pool.size(), "PARAMETER_RANGE_REJECTED");
    auto offset = elf.file_offset(pool_address + std::uint32_t(skip), key_size + iv_size);
    std::vector<std::uint8_t> masked(elf_bytes.begin(), elf_bytes.end());
    std::fill_n(masked.begin() + offset, key_size + iv_size, std::uint8_t(0));
    require(sha256(masked) == (sm4?sm4_masked_sha:masked_sha), "MODULE_IMAGE_PROFILE_UNKNOWN");
    ModuleMaterial material;
    material.algorithm_id = std::move(algorithm_id);
    if(sm4){
        material.cipher=ModuleCipher::sm4_cbc;
        std::copy_n(pool.begin()+skip,16,material.sm4_key.begin());
        std::copy_n(pool.begin()+skip+key_size,16,material.sm4_iv.begin());
    }else{
        std::copy_n(pool.begin() + skip, material.key.size(), material.key.begin());
        std::copy_n(pool.begin() + skip + key_size, material.iv.size(), material.iv.begin());
    }
    return material;
}
}

ModuleMaterial decode_module(Bytes module, Bytes native_library) {
    try {
        require(!native_library.empty() && native_library.size() <= max_input && sha256(native_library) == native_sha,
                "NATIVE_LIBRARY_PROFILE_UNKNOWN");
        auto header = parse_header(module);
        auto elf = unpack(module, native_library, header);
        return inspect(elf, std::move(header.algorithm_id));
    } catch (const ModuleError&) { throw; }
      catch (const std::bad_alloc&) { throw ModuleError("MODULE_RESOURCE_LIMIT"); }
      catch (...) { throw ModuleError("MODULE_DECODE_FAILED"); }
}

ModuleSummary summarize_module(Bytes module) {
    require(!module.empty()&&module.size()<=max_input,"MODULE_FORMAT_UNKNOWN");
    ModuleSummary result{module.size(),sha256(module),-1};
    if(module.size()<5||module[0]!='0'||module[1]!='0'||module[2]!='1')return result;
    std::size_t position=3;
    for(unsigned field=0;field<2;++field){
        if(position>=module.size())return result;
        auto size=module[position++];if(size>module.size()-position)return result;position+=size;
    }
    if(module.size()-position>=9)result.encoding=int(u32(module,position+5)>>28);
    return result;
}

std::vector<std::uint8_t> read_known_native_library(const std::filesystem::path& path) {
    try {
        std::error_code error;
        require(std::filesystem::is_regular_file(path, error) && !error, "NATIVE_LIBRARY_UNREADABLE");
        std::ifstream stream(path, std::ios::binary | std::ios::ate);
        require(bool(stream), "NATIVE_LIBRARY_UNREADABLE");
        auto length = stream.tellg();
        require(length > 0 && length <= std::streamoff(max_input), "NATIVE_LIBRARY_SIZE_REJECTED");
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
        stream.seekg(0);
        stream.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
        require(stream.gcount() == static_cast<std::streamsize>(bytes.size()), "NATIVE_LIBRARY_UNREADABLE");
        char extra;
        require(!stream.get(extra), "NATIVE_LIBRARY_SIZE_REJECTED");
        require(sha256(bytes) == native_sha, "NATIVE_LIBRARY_PROFILE_UNKNOWN");
        return bytes;
    } catch (const ModuleError&) { throw; }
      catch (const std::bad_alloc&) { throw ModuleError("MODULE_RESOURCE_LIMIT"); }
      catch (...) { throw ModuleError("NATIVE_LIBRARY_UNREADABLE"); }
}
}
