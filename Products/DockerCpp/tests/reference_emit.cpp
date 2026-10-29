#include "esurfing/cipher.hpp"
#include "esurfing/cipher_registry.hpp"
#include <iostream>
int main(){for(const auto& spec:esurfing::cipher_specs){for(unsigned length:{1u,16u,17u,32u,33u}){
    esurfing::Cipher cipher(spec.id);std::cout<<spec.id<<'\t'<<length<<'\t'<<cipher.encrypt(std::string(length,'a'))<<'\n';
}}}
