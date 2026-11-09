#include "esurfing/cipher.hpp"
#include <algorithm>
#include <array>
#include <iostream>
#include <stdexcept>
extern "C" {
#include "../third_party/ciphers/algo/sm4_common.h"
}

using namespace esurfing;
namespace {
unsigned checks=0;
void check(bool ok){++checks;if(!ok)throw std::runtime_error("SM4_NATIVE_REGRESSION_FAILED");}
template<class F>void rejects(F f,std::string_view code){try{f();}catch(const std::exception& e){check(e.what()==code);return;}throw std::runtime_error("SM4_REJECTION_EXPECTED");}
std::string xor_byte(std::string text,std::size_t byte,unsigned mask){
    constexpr char hex[]="0123456789ABCDEF";
    auto digit=[](char c){return c<='9'?c-'0':c-'A'+10;};
    unsigned value=unsigned(digit(text[byte*2])*16+digit(text[byte*2+1]))^mask;
    text[byte*2]=hex[value>>4];text[byte*2+1]=hex[value&15];return text;
}
// 构造精确解密边界/损坏填充；独立调用单块原语，不走生产encrypt的NUL处理。
std::string padded_cipher(std::string input,const std::array<std::uint8_t,16>& key,std::array<std::uint8_t,16> chain){
    if(input.empty()||input.size()%16)throw std::runtime_error("TEST_PADDING_SIZE");
    std::array<std::uint32_t,32> keys{};sm4_key_expansion(key.data(),keys.data());
    std::string output;constexpr char hex[]="0123456789ABCDEF";
    for(std::size_t at=0;at<input.size();at+=16){
        std::array<std::uint8_t,16> block{};
        for(unsigned i=0;i<16;++i)block[i]=std::uint8_t(input[at+i])^chain[i];
        sm4_encrypt_block(block.data(),chain.data(),keys.data());
        for(auto b:chain){output+=hex[b>>4];output+=hex[b&15];}
    }
    return output;
}
}
int main(){try{
    // 公开的标准SM4单块向量，不涉及现场参数。
    const std::array<std::uint8_t,16> standard_key{0x01,0x23,0x45,0x67,0x89,0xab,0xcd,0xef,0xfe,0xdc,0xba,0x98,0x76,0x54,0x32,0x10};
    const std::array<std::uint8_t,16> standard_cipher{0x68,0x1e,0xdf,0x34,0xd2,0x06,0x96,0x5e,0x86,0xb3,0xe9,0x4f,0x53,0x6e,0x42,0x46};
    std::array<std::uint32_t,32> round_keys{};std::array<std::uint8_t,16> block{},plain{};
    sm4_key_expansion(standard_key.data(),round_keys.data());
    sm4_encrypt_block(standard_key.data(),block.data(),round_keys.data());check(block==standard_cipher);
    sm4_decrypt_block(block.data(),plain.data(),round_keys.data());check(plain==standard_key);

    // 以下黄金密文由隔离原生模块使用显式替换的公开合成参数生成。
    // key=00..0f，IV=a0..af；不是现场密钥，也不是本C++实现生成的预期值。
    std::array<std::uint8_t,16> key{},iv{};
    for(unsigned i=0;i<16;++i){key[i]=std::uint8_t(i);iv[i]=std::uint8_t(0xa0+i);}
    constexpr auto id="11111111-1111-4111-8111-111111111111";
    Cipher cipher(id,key,iv);
    constexpr auto empty_cipher="7345D70EB896731B045DFBD023BE47F6";
    constexpr auto eight_cipher="7060BB73324C08A227FAC5EC3FDF6B46";
    const std::string unicode="<response><ticket>\xe6\xb5\x8b\xe8\xaf\x95&amp;synthetic</ticket></response>";
    constexpr auto unicode_cipher="4670F269D83C7D54128B59A97789E4B05ED6F27D9F8EF1055F5AD4E13B893F5ABFBF8B1682B522170F052436EB490F5D916EB804B4B63FF8CA8F9D5D9FED5619";
    check(cipher.algorithm_id()==id&&cipher.uses_native_xml_profile());
    check(cipher.decrypt(empty_cipher).empty());
    check(cipher.encrypt("qqqqqqqq")==eight_cipher);check(cipher.decrypt(eight_cipher)=="qqqqqqqq");
    check(cipher.encrypt(unicode)==unicode_cipher);check(cipher.decrypt(unicode_cipher)==unicode);
    // 同一隔离原生模块、公开合成参数生成；原生DeCode逐个验证，无终止NUL。
    constexpr std::array<std::string_view,16> raw_response_golden{
        "4862E0E409CFD067D00CEE7F1CD10218","24CAC2EF181C42B3ED9E2BA44389CBF5",
        "5ED7878AA2CD19C9947255B0268891F7","DA9A71317BC1CE21825B8814CB9B8F37",
        "37CCD88EF18B9ABAC54E6BA22552A2A8","AD90B5C6A3FDE3E61BD04D82AF9FD6DE",
        "77C263779265370DE87B6B2CA8519212","0AAEFA3C85699B28AF8F82F4B5268335",
        "EA3393B8402D2F55458DF2AEE2C7EDC6","C44676525B5605BCE5088A6210DE1984",
        "52B90C2AFB24F27BD2CEE618643426AE","E2CE2CC1C4B738D4E4DDEFB8166F7F93",
        "7E1BE273F9F9C0FE55E0D5D724E260D8","20D99C4BAB9048FCC813554BF1F94969",
        "C552063BB1B3B0AAFAEF27F51DCB23A5","A69EB37285B8F5BAB9FA3AFC89F414B2A6607DA8D84D62B423EC9BCD6DBAC63C"};
    for(std::size_t n=0;n<raw_response_golden.size();++n)check(cipher.decrypt(raw_response_golden[n])==std::string(n+1,'q'));
    check(cipher.decrypt("4670F269D83C7D54128B59A97789E4B05ED6F27D9F8EF1055F5AD4E13B893F5ABFBF8B1682B522170F052436EB490F5DCF61B6D6A9B80C2AE8BC9E0A48E4AA2A")==unicode);
    check(cipher.decrypt(padded_cipher(std::string(16,char(16)),key,iv)).empty());
    check(cipher.decrypt(padded_cipher(std::string(65536,'q')+std::string(16,char(16)),key,iv))==std::string(65536,'q'));
    rejects([&]{cipher.decrypt(padded_cipher(std::string(65537,'q')+std::string(15,char(15)),key,iv));},"PLAINTEXT_SIZE_INVALID");
    rejects([&]{cipher.decrypt(padded_cipher(std::string("x\0y",3)+std::string(13,char(13)),key,iv));},"PLAINTEXT_NUL_INVALID");
    rejects([&]{cipher.decrypt(padded_cipher(std::string("x\0\0",3)+std::string(13,char(13)),key,iv));},"PLAINTEXT_NUL_INVALID");
    for(unsigned padding:{0,17,255})rejects([&]{cipher.decrypt(padded_cipher(std::string(15,'q')+char(padding),key,iv));},"CIPHERTEXT_PADDING_INVALID");
    rejects([&]{cipher.decrypt(padded_cipher(std::string(14,'q')+char(1)+char(2),key,iv));},"CIPHERTEXT_PADDING_INVALID");
    for(auto n:{1,7,8,9,14,15,16,17,30,31,32,33,255,256,257,4096,65536}){
        std::string value(n,'x');auto encrypted=cipher.encrypt(value);
        check(encrypted.size()==std::size_t(n+1+16-(n+1)%16)*2&&cipher.decrypt(encrypted)==value);
    }
    auto altered=key;altered[0]^=1;Cipher other(id,altered,iv);
    check(other.encrypt("qqqqqqqq")!=eight_cipher&&cipher.encrypt("qqqqqqqq")==eight_cipher);
    Cipher renamed("22222222-2222-4222-8222-222222222222",key,iv);
    check(renamed.encrypt("qqqqqqqq")==eight_cipher);
    rejects([&]{cipher.encrypt("");},"PLAINTEXT_SIZE_INVALID");
    rejects([&]{cipher.encrypt(std::string("x\0y",3));},"PLAINTEXT_SIZE_INVALID");
    rejects([&]{cipher.encrypt(std::string(65537,'x'));},"PLAINTEXT_SIZE_INVALID");
    rejects([&]{cipher.decrypt("00");},"CIPHERTEXT_SIZE_INVALID");
    rejects([&]{cipher.decrypt(std::string(32,'Z'));},"CIPHERTEXT_HEX_INVALID");
    rejects([&]{cipher.decrypt(std::string(65568*2,'0'));},"CIPHERTEXT_SIZE_INVALID");
    // CBC前一密文块定点修改：只破坏下一块指定字节，尾块其余padding保留。
    auto two_blocks=cipher.encrypt(std::string(16,'x'));
    rejects([&]{cipher.decrypt(xor_byte(two_blocks,1,1));},"CIPHERTEXT_PADDING_INVALID");
    auto three_blocks=cipher.encrypt(std::string(32,'x'));
    rejects([&]{cipher.decrypt(xor_byte(three_blocks,0,unsigned('x')));},"PLAINTEXT_NUL_INVALID");
    std::cout<<checks<<" SM4 native checks passed; public synthetic native golden vectors, no server access\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
