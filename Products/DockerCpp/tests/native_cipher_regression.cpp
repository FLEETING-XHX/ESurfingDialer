#include "esurfing/cipher.hpp"
#include "esurfing/native_cipher.hpp"
#include <array>
#include <atomic>
#include <iostream>
#include <thread>
#include <vector>

using namespace esurfing;
namespace {
unsigned checks=0;
void check(bool ok){++checks;if(!ok)throw std::runtime_error("NATIVE_CIPHER_REGRESSION_FAILED");}
template<class F>void rejects(F f,std::string_view code){try{f();}catch(const std::exception& e){check(e.what()==code);return;}throw std::runtime_error("NATIVE_CIPHER_REJECTION_EXPECTED");}
}
int main(){try{
    // 公开的合成参数，与现场参数无关。密文来自同代码、替换此参数的
    // 隔离原生primitive；不是由本C++实现生成的预期值。
    std::array<std::uint8_t,48> key{};std::array<std::uint8_t,8> iv{};
    for(unsigned i=0;i<key.size();++i)key[i]=std::uint8_t(i);
    for(unsigned i=0;i<iv.size();++i)iv[i]=std::uint8_t(0xa0+i);
    Cipher cipher("11111111-1111-4111-8111-111111111111",key,iv);
    constexpr auto eight="4E5F697017595A87D3EE8669A5ECA274";
    const std::string unicode="<response><ticket>\xe6\xb5\x8b\xe8\xaf\x95&amp;synthetic</ticket></response>";
    constexpr auto unicode_cipher="4F5A0F15C8AFF6CC16FA0CD06BE28A16E1D1B2DAEFBF8071C9A18BE5B2296EEEB9E866575BC08F1EC060A7741B9B40DD1A13A9602B7A88833E43891C0627E653";
    check(cipher.encrypt("qqqqqqqq")==eight);check(cipher.decrypt(eight)=="qqqqqqqq");
    check(cipher.encrypt(unicode)==unicode_cipher);check(cipher.decrypt(unicode_cipher)==unicode);
    check(cipher.decrypt("D63037F767D8CCA9").empty());
    native::TripleModifiedTeaCbc primitive(key,iv);auto empty=primitive.encrypt_c_string("");
    check(empty==std::vector<std::uint8_t>({0xd6,0x30,0x37,0xf7,0x67,0xd8,0xcc,0xa9}));
    auto changed=key;changed[0]^=1;Cipher other("11111111-1111-4111-8111-111111111111",changed,iv);
    check(other.encrypt("qqqqqqqq")!=eight&&cipher.encrypt("qqqqqqqq")==eight);
    std::atomic<bool> consistent=true;std::vector<std::thread> threads;
    for(unsigned i=0;i<4;++i)threads.emplace_back([&]{try{for(unsigned j=0;j<50;++j)if(cipher.encrypt("qqqqqqqq")!=eight||cipher.decrypt(eight)!="qqqqqqqq")consistent=false;}catch(...){consistent=false;}});
    for(auto& t:threads)t.join();check(consistent);
    std::string broken=eight;broken[2]=broken[2]=='0'?'1':'0';
    rejects([&]{cipher.decrypt(broken);},"CIPHERTEXT_PADDING_INVALID");
    rejects([&]{cipher.decrypt("ZZ00000000000000");},"CIPHERTEXT_HEX_INVALID");
    rejects([&]{cipher.decrypt("00");},"CIPHERTEXT_SIZE_INVALID");
    rejects([&]{cipher.encrypt(std::string("x\0y",3));},"PLAINTEXT_SIZE_INVALID");
    rejects([&]{cipher.encrypt(std::string(65537,'q'));},"PLAINTEXT_SIZE_INVALID");
    check(cipher.decrypt(cipher.encrypt(std::string(65536,'q')))==std::string(65536,'q'));
    std::cout<<checks<<" native cipher checks passed; synthetic native golden vectors, no server access\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
