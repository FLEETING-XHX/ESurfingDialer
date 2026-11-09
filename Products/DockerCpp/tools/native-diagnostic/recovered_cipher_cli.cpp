#include "recovered_cipher.hpp"
#include <algorithm>
#include <array>
#include <fstream>
#include <filesystem>
#include <iostream>
#include <iterator>
#include <sstream>
#include <string>
#include <stdexcept>

namespace {
unsigned digit(char c){if(c>='0'&&c<='9')return c-'0';if(c>='a'&&c<='f')return c-'a'+10;if(c>='A'&&c<='F')return c-'A'+10;throw std::runtime_error("INVALID_HEX");}
std::string unhex(const std::string& s){if(s.size()%2)throw std::runtime_error("INVALID_HEX");std::string out;for(std::size_t i=0;i<s.size();i+=2)out+=char(digit(s[i])*16+digit(s[i+1]));return out;}
std::string hex(std::span<const std::uint8_t> bytes){constexpr char digits[]="0123456789ABCDEF";std::string out;for(auto b:bytes){out+=digits[b>>4];out+=digits[b&15];}return out;}
}
int main(int argc,char**argv){try{
    if(argc!=4&&argc!=5)throw std::runtime_error("USAGE_PARAMS_VECTORS_MODE_OUTPUT");
    std::ifstream params(argv[1],std::ios::binary);std::vector<std::uint8_t> material((std::istreambuf_iterator<char>(params)),{});
    if(material.size()!=56)throw std::runtime_error("INVALID_PRIVATE_PARAMETERS");
    recovered::TripleModifiedTeaCbc cipher(std::span<const std::uint8_t,48>(material.data(),48),std::span<const std::uint8_t,8>(material.data()+48,8));
    std::ifstream input(argv[2]);if(!input)throw std::runtime_error("INPUT_MISSING");
    std::string mode=argv[3],line;std::getline(input,line);unsigned count=0;
    std::ofstream generated;
    if(mode=="generate"){
        if(argc!=5)throw std::runtime_error("OUTPUT_MISSING");
        if(std::filesystem::exists(argv[4]))throw std::runtime_error("OUTPUT_EXISTS");
        generated.open(argv[4]);if(!generated)throw std::runtime_error("OUTPUT_OPEN_FAILED");
        generated<<"case\tinput_utf8_hex\tciphertext_hex\n";
    }
    while(std::getline(input,line)){
        if(!line.empty()&&line.back()=='\r')line.pop_back();
        std::string id,inHex,outHex;
        auto first=line.find('\t');if(first==line.npos)throw std::runtime_error("ROW_INVALID");
        auto second=line.find('\t',first+1);id=line.substr(0,first);
        inHex=line.substr(first+1,second==line.npos?line.npos:second-first-1);
        const auto plain=unhex(inHex);auto encrypted=cipher.encrypt_c_string(plain);
        if(mode=="verify"){
            if(second==line.npos)throw std::runtime_error("ROW_INVALID");outHex=line.substr(second+1);
            auto nativeHex=unhex(outHex);
            std::transform(nativeHex.begin(),nativeHex.end(),nativeHex.begin(),[](unsigned char c){return char(c>='a'&&c<='f'?c-'a'+'A':c);});
            if(hex(encrypted)!=nativeHex)throw std::runtime_error("NATIVE_ENCRYPT_MISMATCH");
            const auto decoded=cipher.decrypt_padded(encrypted);
            if(decoded.size()!=encrypted.size() || !std::equal(plain.begin(),plain.end(),decoded.begin(),[](char a,std::uint8_t b){return std::uint8_t(a)==b;}) ||
                !std::all_of(decoded.begin()+plain.size(),decoded.end(),[](auto value){return value==0;}))throw std::runtime_error("DECRYPT_PADDED_MISMATCH");
        }else if(mode=="generate")generated<<id<<'\t'<<inHex<<'\t'<<hex(encrypted)<<'\n';
        else throw std::runtime_error("INVALID_MODE");
        ++count;
    }
    if(count==0)throw std::runtime_error("EMPTY_VECTOR_SET");
    if(mode=="generate" && !generated)throw std::runtime_error("OUTPUT_WRITE_FAILED");
    std::cout<<"recovered_cpp mode="<<mode<<" vectors="<<count<<" passed; private parameters not printed\n";
    return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
