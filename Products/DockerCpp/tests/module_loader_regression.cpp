#include "esurfing/module_loader.hpp"
#include <algorithm>
#include <fstream>
#include <functional>
#include <iostream>
#include <string_view>

namespace {
unsigned checks = 0;
void check(bool value) { if (!value) throw std::runtime_error("MODULE_REGRESSION_FAILED"); ++checks; }
void rejects(const std::function<void()>& operation, std::string_view code) {
    try { operation(); }
    catch (const esurfing::ModuleError& error) { check(std::string_view(error.code()) == code); return; }
    throw std::runtime_error("MODULE_REGRESSION_NOT_REJECTED");
}
std::vector<std::uint8_t> input(const char* path, std::size_t maximum = 1024 * 1024) {
    std::ifstream file(path, std::ios::binary | std::ios::ate);
    if (!file) throw std::runtime_error("PRIVATE_INPUT_UNAVAILABLE");
    auto size = file.tellg();
    if (size < 0 || size > std::streamoff(maximum)) throw std::runtime_error("PRIVATE_INPUT_SIZE_INVALID");
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
    file.seekg(0);
    file.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    if (file.gcount() != static_cast<std::streamsize>(bytes.size())) throw std::runtime_error("PRIVATE_INPUT_UNREADABLE");
    return bytes;
}
void set_word(std::vector<std::uint8_t>& bytes, std::size_t position, std::uint32_t word) {
    if (position > bytes.size() || bytes.size() - position < 4) throw std::runtime_error("PRIVATE_INPUT_HEADER_INVALID");
    for (unsigned i = 0; i < 4; ++i) bytes[position + i] = std::uint8_t(word >> (i * 8));
}
std::vector<std::uint8_t> parameters(const esurfing::ModuleMaterial& material){
    if(material.cipher==esurfing::ModuleCipher::sm4_cbc){std::vector<std::uint8_t> result(material.sm4_key.begin(),material.sm4_key.end());result.insert(result.end(),material.sm4_iv.begin(),material.sm4_iv.end());return result;}
    std::vector<std::uint8_t> result(material.key.begin(),material.key.end());result.insert(result.end(),material.iv.begin(),material.iv.end());return result;
}
}

int main(int argc, char** argv) {
    // 不把真实模块/原生库加入仓库；无私有材料时返回CTest可选跳过码77。
    if (argc == 1) { std::cout << "Private module/native-library inputs required; skipped\n"; return 77; }
    if (argc != 3 && argc != 7) {
        std::cerr << "module-loader-regression module native-library [header-variant pool-variant expected-parameters unknown-code-module]\n";
        return 2;
    }
    try {
        auto module = input(argv[1]);
        auto library = esurfing::read_known_native_library(argv[2]);
        auto material = esurfing::decode_module(module, library);
        check(material.algorithm_id.size() == 36 && material.key.size() == 48 && material.iv.size() == 8);
        std::size_t position = 3;
        if (module.size() < 5 || position + 1 + module[position] >= module.size()) throw std::runtime_error("PRIVATE_INPUT_HEADER_INVALID");
        position += std::size_t(module[position]) + 1;
        auto id_count = module[position++];
        if (id_count != 36 || id_count > module.size() - position) throw std::runtime_error("PRIVATE_INPUT_HEADER_INVALID");
        auto id_offset = position;
        auto body = position + id_count;
        if (body + 9 >= module.size()) throw std::runtime_error("PRIVATE_INPUT_HEADER_INVALID");
        auto changed = module;
        std::string replacement = material.algorithm_id == "11111111-1111-4111-8111-111111111111"
            ? "22222222-2222-4222-8222-222222222222" : "11111111-1111-4111-8111-111111111111";
        std::copy(replacement.begin(), replacement.end(), changed.begin() + id_offset);
        auto rotated = esurfing::decode_module(changed, library);
        check(rotated.algorithm_id == replacement && rotated.cipher==material.cipher&&parameters(rotated)==parameters(material));
        auto unknown_library = library;
        unknown_library.back() ^= 1;
        rejects([&] { esurfing::decode_module(module, unknown_library); }, "NATIVE_LIBRARY_PROFILE_UNKNOWN");
        rejects([&] { esurfing::decode_module(std::vector<std::uint8_t>(4), library); }, "MODULE_FORMAT_UNKNOWN");
        rejects([&] { esurfing::decode_module(std::vector<std::uint8_t>(1024 * 1024 + 1), library); }, "MODULE_FORMAT_UNKNOWN");
        changed = module; set_word(changed, body + 5, 0x40009700);
        rejects([&] { esurfing::decode_module(changed, library); }, "MODULE_ENCODING_PROFILE_UNKNOWN");
        changed = module; set_word(changed, body + 5, 0x30100001);
        rejects([&] { esurfing::decode_module(changed, library); }, "MODULE_OUTPUT_SIZE_REJECTED");
        changed = module; set_word(changed, body + 1, 16 * 1024 * 1024 + 1);
        rejects([&] { esurfing::decode_module(changed, library); }, "MODULE_LZMA_PROPERTIES_REJECTED");
        changed = module;
        const bool type0=(std::uint32_t(module[body+8])>>4)==0;
        if(type0)changed.resize(body+9+4);else changed.pop_back();
        rejects([&] { esurfing::decode_module(changed, library); }, type0?"MODULE_STREAM_TOO_SHORT":"MODULE_STREAM_ALIGNMENT_REJECTED");
        rejects([&] { esurfing::read_known_native_library(std::filesystem::path()); }, "NATIVE_LIBRARY_UNREADABLE");
        if (argc == 7) {
            auto header_material = esurfing::decode_module(input(argv[3]), library);
            check(header_material.algorithm_id != material.algorithm_id && header_material.cipher==material.cipher&&parameters(header_material)==parameters(material));
            auto expected_parameters = input(argv[5], 56);
            check(expected_parameters.size() == parameters(material).size());
            auto pool_material = esurfing::decode_module(input(argv[4]), library);
            check(pool_material.algorithm_id == material.algorithm_id && pool_material.cipher==material.cipher&&parameters(pool_material)==expected_parameters);
            auto unknown_code = input(argv[6]);
            rejects([&] { esurfing::decode_module(unknown_code, library); }, "MODULE_CODE_PROFILE_UNKNOWN");
        }
        std::cout << checks << " module loader checks passed; no ARM execution or server access\n";
        return 0;
    } catch (const esurfing::ModuleError& error) { std::cerr << error.code() << '\n'; return 1; }
      catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
