#include "esurfing/sm4_cipher.hpp"
#include <algorithm>
#include <stdexcept>
extern "C" {
#include "../third_party/ciphers/algo/sm4_common.h"
}

namespace esurfing::native {
Sm4Cbc::Sm4Cbc(std::span<const std::uint8_t,16> key,std::span<const std::uint8_t,16> iv) {
    sm4_key_expansion(key.data(),round_keys_.data());
    std::copy(iv.begin(),iv.end(),iv_.begin());
}

std::vector<std::uint8_t> Sm4Cbc::encrypt_c_string(std::string_view text) const {
    if(text.size()>65536||text.find('\0')!=text.npos)throw std::invalid_argument("PLAINTEXT_SIZE_INVALID");
    // 原生Code传strlen(serialized_xml)+1，包括NUL，再PKCS7补到16字节倍数。
    const auto count=std::uint8_t(16-(text.size()+1)%16);
    std::vector<std::uint8_t> result(text.size()+1+count,count);
    std::copy(text.begin(),text.end(),result.begin());result[text.size()]=0;
    auto chain=iv_;
    for(std::size_t offset=0;offset<result.size();offset+=16){
        for(unsigned i=0;i<16;++i)result[offset+i]^=chain[i];
        sm4_encrypt_block(result.data()+offset,result.data()+offset,round_keys_.data());
        std::copy_n(result.begin()+offset,16,chain.begin());
    }
    return result;
}

std::string Sm4Cbc::decrypt_c_string(std::span<const std::uint8_t> ciphertext) const {
    if(ciphertext.empty()||ciphertext.size()>65552||ciphertext.size()%16)throw std::invalid_argument("CIPHERTEXT_SIZE_INVALID");
    std::vector<std::uint8_t> result(ciphertext.size());auto chain=iv_;
    for(std::size_t offset=0;offset<ciphertext.size();offset+=16){
        sm4_decrypt_block(ciphertext.data()+offset,result.data()+offset,round_keys_.data());
        for(unsigned i=0;i<16;++i)result[offset+i]^=chain[i];
        std::copy_n(ciphertext.begin()+offset,16,chain.begin());
    }
    const auto padding=result.back();
    if(padding<1||padding>16||!std::all_of(result.end()-padding,result.end(),[&](auto b){return b==padding;}))
        throw std::runtime_error("CIPHERTEXT_PADDING_INVALID");
    result.resize(result.size()-padding);
    // Code的输入包含C字符串终止NUL，但DeCode也接受直接PKCS7填充的XML。
    // 原生解密会清零padding作为字符串终止符；不能要求响应原文自带NUL。
    if(!result.empty()&&result.back()==0)result.pop_back();
    if(std::find(result.begin(),result.end(),std::uint8_t(0))!=result.end())
        throw std::runtime_error("PLAINTEXT_NUL_INVALID");
    if(result.size()>65536)throw std::runtime_error("PLAINTEXT_SIZE_INVALID");
    return std::string(result.begin(),result.end());
}
}
