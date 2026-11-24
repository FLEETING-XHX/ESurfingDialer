#include "esurfing/aes_cipher.hpp"
#include <algorithm>
#include <stdexcept>
extern "C" {
void aes128_expand_key(const std::uint8_t*, std::uint8_t*);
void aes128_encrypt_block_rk(const std::uint8_t*,const std::uint8_t*,std::uint8_t*);
void aes128_decrypt_block_rk(const std::uint8_t*,const std::uint8_t*,std::uint8_t*);
}
namespace esurfing::native {
namespace {
// Each layer prefixes its IV and pads with zeros, without PKCS7.
std::vector<std::uint8_t> encrypt(std::span<const std::uint8_t> input,
    const std::array<std::uint8_t,176>& key,const std::array<std::uint8_t,16>& iv) {
    std::vector<std::uint8_t> out(16+(input.size()+15)/16*16);
    std::copy(iv.begin(),iv.end(),out.begin());
    std::copy(input.begin(),input.end(),out.begin()+16);
    for(std::size_t i=16;i<out.size();i+=16){
        for(std::size_t j=0;j<16;++j)out[i+j]^=out[i-16+j];
        aes128_encrypt_block_rk(key.data(),out.data()+i,out.data()+i);
    }
    return out;
}
std::vector<std::uint8_t> decrypt(std::span<const std::uint8_t> input,
    const std::array<std::uint8_t,176>& key) {
    if(input.size()<32||input.size()%16)throw std::runtime_error("CIPHERTEXT_SIZE_INVALID");
    std::vector<std::uint8_t> out(input.size()-16);
    for(std::size_t i=16;i<input.size();i+=16){
        aes128_decrypt_block_rk(key.data(),input.data()+i,out.data()+i-16);
        for(std::size_t j=0;j<16;++j)out[i-16+j]^=input[i-16+j];
    }
    return out;
}
}
DoubleAes128Cbc::DoubleAes128Cbc(const std::array<std::uint8_t,32>& keys,const std::array<std::uint8_t,16>& iv):iv_(iv){
    aes128_expand_key(keys.data(),first_.data());
    aes128_expand_key(keys.data()+16,second_.data());
}
std::vector<std::uint8_t> DoubleAes128Cbc::encrypt_c_string(std::string_view text) const {
    if(text.empty()||text.size()>65536||text.find('\0')!=std::string_view::npos)throw std::runtime_error("PLAINTEXT_SIZE_INVALID");
    std::vector<std::uint8_t> input(text.begin(),text.end());input.push_back(0);
    return encrypt(encrypt(input,first_,iv_),second_,iv_);
}
std::string DoubleAes128Cbc::decrypt_c_string(std::span<const std::uint8_t> bytes) const {
    if(bytes.size()<48||bytes.size()>65584)throw std::runtime_error("CIPHERTEXT_SIZE_INVALID");
    auto outer=decrypt(bytes,second_);
    auto plain=decrypt(outer,first_);
    auto end=std::find(plain.begin(),plain.end(),std::uint8_t(0));
    if(end==plain.end()||!std::all_of(end,plain.end(),[](auto c){return c==0;}))throw std::runtime_error("CIPHERTEXT_PADDING_INVALID");
    if(std::size_t(end-plain.begin())>65536)throw std::runtime_error("PLAINTEXT_SIZE_INVALID");
    return {plain.begin(),end};
}
}
