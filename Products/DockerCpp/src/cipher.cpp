#include "esurfing/cipher.hpp"
#include "esurfing/cipher_registry.hpp"
#include "esurfing/native_cipher.hpp"
#include "esurfing/sm4_cipher.hpp"
#include <algorithm>
#include <cstdlib>
#include <mutex>
#include <optional>
#include <stdexcept>
extern "C" {
#include "cipher/CipherInterface.h"
cipher_interface_t* es_create_cipher(const char*);
}
namespace esurfing {
static bool uuid(std::string_view text) {
    if(text.size() != 36) return false;
    for(std::size_t i = 0; i < text.size(); ++i) {
        char c = text[i];
        if(i==8 || i==13 || i==18 || i==23) { if(c != '-') return false; }
        else if(!((c>='0'&&c<='9')||(c>='A'&&c<='F')||(c>='a'&&c<='f'))) return false;
    }
    return true;
}
static std::string upper(std::string_view s) {
    std::string result(s);
    for(char& c:result) if(c>='a'&&c<='f') c = static_cast<char>(c-'a'+'A');
    return result;
}
std::string module_candidate(std::span<const std::uint8_t> module) {
    if(module.size() < 5 || module.size() > 1024*1024) throw std::runtime_error("BOOTSTRAP_SIZE_INVALID");
    std::size_t pos = 3;
    auto pascal = [&]() {
        if(pos >= module.size()) throw std::runtime_error("ZSM_HEADER_INVALID");
        auto length = module[pos++];
        if(length > module.size()-pos) throw std::runtime_error("ZSM_HEADER_INVALID");
        std::string s(reinterpret_cast<const char*>(module.data()+pos), length);
        pos += length;
        return s;
    };
    auto first = pascal(); auto second = pascal();
    if(uuid(second)) return upper(second);
    if(uuid(first)) return upper(first);
    // Do not guess an ID from an arbitrary UUID elsewhere in an opaque module.
    throw std::runtime_error("ZSM_ALGORITHM_UNSUPPORTED");
}
struct Cipher::Impl {
    cipher_interface_t* raw=nullptr;
    std::size_t block;
    std::string id;
    std::optional<native::TripleModifiedTeaCbc> native;
    std::optional<native::Sm4Cbc> sm4;
    std::mutex mutex;
    Impl(cipher_interface_t* p, std::size_t b, std::string name):raw(p),block(b),id(std::move(name)){}
    Impl(std::string name, const std::array<std::uint8_t,48>& key,
         const std::array<std::uint8_t,8>& iv):block(8),id(std::move(name)) {
        native.emplace(std::span<const std::uint8_t,48>(key),std::span<const std::uint8_t,8>(iv));
    }
    Impl(std::string name,const std::array<std::uint8_t,16>& key,const std::array<std::uint8_t,16>& iv):block(16),id(std::move(name)){
        sm4.emplace(std::span<const std::uint8_t,16>(key),std::span<const std::uint8_t,16>(iv));
    }
    ~Impl() { if(raw) raw->destroy(raw); }
};
Cipher::Cipher(std::string_view id) {
    if(!uuid(id)) throw std::invalid_argument("ALGORITHM_ID_INVALID");
    auto name = upper(id);
    const CipherSpec* spec = nullptr;
    for(const auto& entry:cipher_specs) if(name == entry.id) { spec = &entry; break; }
    if(!spec) throw std::runtime_error("ALGORITHM_UNSUPPORTED");
    auto raw = es_create_cipher(name.c_str());
    if(!raw) throw std::runtime_error("CIPHER_INIT_FAILED");
    try { impl_ = std::make_unique<Impl>(raw, spec->block, name); }
    catch(...) { raw->destroy(raw); throw; }
}
Cipher::Cipher(std::string_view id, const std::array<std::uint8_t,48>& key,
               const std::array<std::uint8_t,8>& iv) {
    if(!uuid(id)) throw std::invalid_argument("ALGORITHM_ID_INVALID");
    impl_=std::make_unique<Impl>(upper(id),key,iv);
}
Cipher::~Cipher() = default;
Cipher::Cipher(std::string_view id,const std::array<std::uint8_t,16>& key,const std::array<std::uint8_t,16>& iv){
    if(!uuid(id))throw std::invalid_argument("ALGORITHM_ID_INVALID");
    impl_=std::make_unique<Impl>(upper(id),key,iv);
}
Cipher::Cipher(Cipher&&) noexcept = default;
Cipher& Cipher::operator=(Cipher&&) noexcept = default;
std::string Cipher::encrypt(std::string_view xml) {
    if(!impl_) throw std::runtime_error("CIPHER_CLOSED");
    if(xml.empty() || xml.size()>64*1024 || xml.find('\0')!=std::string_view::npos) throw std::runtime_error("PLAINTEXT_SIZE_INVALID");
    std::lock_guard lock(impl_->mutex);
    if(impl_->native||impl_->sm4) {
        constexpr char digits[]="0123456789ABCDEF";
        const auto bytes=impl_->sm4?impl_->sm4->encrypt_c_string(xml):impl_->native->encrypt_c_string(xml);
        std::string result;result.reserve(bytes.size()*2);
        for(auto value:bytes) { result+=digits[value>>4];result+=digits[value&15]; }
        return result;
    }
    std::string input(xml);
    std::unique_ptr<char, decltype(&std::free)> result(impl_->raw->encrypt(impl_->raw, input.c_str()), &std::free);
    if(!result) throw std::runtime_error("ENCRYPT_FAILED");
    return result.get();
}
std::string Cipher::decrypt(std::string_view hex) {
    if(!impl_) throw std::runtime_error("CIPHER_CLOSED");
    if(hex.empty() || hex.size()>256*1024 || hex.size()%(impl_->block*2)!=0) throw std::runtime_error("CIPHERTEXT_SIZE_INVALID");
    if(!std::all_of(hex.begin(), hex.end(), [](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F');}))
        throw std::runtime_error("CIPHERTEXT_HEX_INVALID");
    std::lock_guard lock(impl_->mutex);
    if(impl_->native||impl_->sm4) {
        if(hex.size()>(impl_->sm4?65552:65544)*2) throw std::runtime_error("CIPHERTEXT_SIZE_INVALID");
        auto digit=[](char c)->unsigned {return c>='0'&&c<='9'?unsigned(c-'0'):
            c>='a'&&c<='f'?unsigned(c-'a'+10):unsigned(c-'A'+10);};
        std::vector<std::uint8_t> bytes;bytes.reserve(hex.size()/2);
        for(std::size_t i=0;i<hex.size();i+=2)bytes.push_back(std::uint8_t(digit(hex[i])*16+digit(hex[i+1])));
        if(impl_->sm4)return impl_->sm4->decrypt_c_string(bytes);
        const auto plain=impl_->native->decrypt_padded(bytes);
        const auto end=std::find(plain.begin(),plain.end(),std::uint8_t(0));
        if(end==plain.end() || !std::all_of(end,plain.end(),[](auto value){return value==0;}))
            throw std::runtime_error("CIPHERTEXT_PADDING_INVALID");
        if(std::size_t(end-plain.begin())>65536)throw std::runtime_error("PLAINTEXT_SIZE_INVALID");
        return std::string(plain.begin(),end);
    }
    std::string input(hex);
    std::unique_ptr<char, decltype(&std::free)> result(impl_->raw->decrypt(impl_->raw, input.c_str()), &std::free);
    if(!result) throw std::runtime_error("DECRYPT_FAILED");
    return result.get();
}
std::string_view Cipher::algorithm_id() const {
    if(!impl_) throw std::runtime_error("CIPHER_CLOSED");
    return impl_->id;
}
bool Cipher::uses_native_xml_profile() const {
    if(!impl_) throw std::runtime_error("CIPHER_CLOSED");
    return impl_->native.has_value()||impl_->sm4.has_value();
}
}
