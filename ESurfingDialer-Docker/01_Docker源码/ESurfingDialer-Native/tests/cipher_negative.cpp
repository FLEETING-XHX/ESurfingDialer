#include "esurfing/cipher.hpp"
#include "esurfing/cipher_registry.hpp"
#include <iostream>
int main(int argc,char**){unsigned cases=0;for(const auto& spec:esurfing::cipher_specs){
    esurfing::Cipher cipher(spec.id);
    for(unsigned blocks=1;blocks<=8;++blocks)for(char digit:{'0','F','5'}){
        if(argc>1)std::cerr<<"CASE "<<spec.id<<' '<<blocks<<' '<<digit<<std::endl;
        try{auto ignored=cipher.decrypt(std::string(spec.block*2*blocks,digit));(void)ignored;}catch(const std::exception&){}
        ++cases;
    }
}std::cout<<cases<<" malformed ciphertext memory-safety cases completed (no semantic acceptance claim)\n";}
