#include "esurfing/native_cipher.hpp"
#include <algorithm>
#include <stdexcept>

namespace esurfing::native {
namespace {
constexpr std::uint32_t delta=0x9e3779b9u;
std::uint32_t read_be(const std::uint8_t* p) {
    return (std::uint32_t(p[0])<<24)|(std::uint32_t(p[1])<<16)|(std::uint32_t(p[2])<<8)|p[3];
}
void write_be(std::uint8_t* p,std::uint32_t v) {
    p[0]=std::uint8_t(v>>24);p[1]=std::uint8_t(v>>16);p[2]=std::uint8_t(v>>8);p[3]=std::uint8_t(v);
}
void enc_layer(std::uint32_t& a,std::uint32_t& b,const std::array<std::uint32_t,4>& key) {
    std::uint32_t sum=0;
    for(unsigned i=0;i<32;++i) {
        a+=(b^sum)+key[sum&3]+((b<<4)^(b>>5));
        sum+=delta;
        b+=key[(sum>>11)&3]+(a^sum)+((a<<4)^(a>>5));
    }
}
void dec_layer(std::uint32_t& a,std::uint32_t& b,const std::array<std::uint32_t,4>& key) {
    std::uint32_t sum=0xc6ef3720u;
    for(unsigned i=0;i<32;++i) {
        b-=(a^sum)+((a<<4)^(a>>5))+key[(sum>>11)&3];
        sum-=delta;
        a-=key[sum&3]+(b^sum)+((b<<4)^(b>>5));
    }
}
}
TripleModifiedTeaCbc::TripleModifiedTeaCbc(std::span<const std::uint8_t,48> key,std::span<const std::uint8_t,8> iv) {
    for(unsigned layer=0;layer<3;++layer)
        for(unsigned i=0;i<4;++i)keys_[layer][i]=read_be(key.data()+layer*16+i*4);
    iv_={read_be(iv.data()),read_be(iv.data()+4)};
}
std::vector<std::uint8_t> TripleModifiedTeaCbc::encrypt_c_string(std::string_view text) const {
    if(text.size()>65536 || text.find('\0')!=text.npos)throw std::invalid_argument("PLAINTEXT_SIZE_INVALID");
    // 原模块Code传strlen(serialized_xml)+1；先包括终止NUL，再补零到8字节倍数。
    std::vector<std::uint8_t> result((text.size()+1+7)&~std::size_t(7),0);
    std::copy(text.begin(),text.end(),result.begin());
    auto chain=iv_;
    for(std::size_t offset=0;offset<result.size();offset+=8) {
        auto a=read_be(result.data()+offset)^chain[0];
        auto b=read_be(result.data()+offset+4)^chain[1];
        enc_layer(a,b,keys_[2]);enc_layer(a,b,keys_[1]);enc_layer(a,b,keys_[0]);
        write_be(result.data()+offset,a);write_be(result.data()+offset+4,b);chain={a,b};
    }
    return result;
}
std::vector<std::uint8_t> TripleModifiedTeaCbc::decrypt_padded(std::span<const std::uint8_t> input) const {
    if(input.empty() || input.size()>65544 || input.size()%8)throw std::invalid_argument("CIPHERTEXT_SIZE_INVALID");
    std::vector<std::uint8_t> result(input.size());auto chain=iv_;
    for(std::size_t offset=0;offset<input.size();offset+=8) {
        const std::array<std::uint32_t,2> current={read_be(input.data()+offset),read_be(input.data()+offset+4)};
        auto a=current[0],b=current[1];
        dec_layer(a,b,keys_[0]);dec_layer(a,b,keys_[1]);dec_layer(a,b,keys_[2]);
        write_be(result.data()+offset,a^chain[0]);write_be(result.data()+offset+4,b^chain[1]);chain=current;
    }
    return result;
}
}
