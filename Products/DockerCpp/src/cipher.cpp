#include "esurfing/cipher.hpp"
#include "esurfing/cipher_registry.hpp"
#include <algorithm>
#include <cstdlib>
#include <mutex>
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
    cipher_interface_t* raw;
    std::size_t block;
    std::mutex mutex;
    Impl(cipher_interface_t* p, std::size_t b):raw(p),block(b){}
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
    try { impl_ = std::make_unique<Impl>(raw, spec->block); }
    catch(...) { raw->destroy(raw); throw; }
}
Cipher::~Cipher() = default;
Cipher::Cipher(Cipher&&) noexcept = default;
Cipher& Cipher::operator=(Cipher&&) noexcept = default;
std::string Cipher::encrypt(std::string_view xml) {
    if(!impl_) throw std::runtime_error("CIPHER_CLOSED");
    if(xml.empty() || xml.size()>64*1024 || xml.find('\0')!=std::string_view::npos) throw std::runtime_error("PLAINTEXT_SIZE_INVALID");
    std::lock_guard lock(impl_->mutex);
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
    std::string input(hex);
    std::unique_ptr<char, decltype(&std::free)> result(impl_->raw->decrypt(impl_->raw, input.c_str()), &std::free);
    if(!result) throw std::runtime_error("DECRYPT_FAILED");
    return result.get();
}
}
