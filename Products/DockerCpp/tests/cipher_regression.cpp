#include "esurfing/cipher.hpp"
#include "esurfing/cipher_registry.hpp"
#include <iostream>
#include <functional>
#include <vector>
#include <fstream>
#include <sstream>
using namespace esurfing;
int main() {
    unsigned tests=0;
    try {
        auto reject=[&](auto fn) { bool rejected=false; try { fn(); } catch(const std::exception&) { rejected=true; }
            ++tests; if(!rejected) throw std::runtime_error("INPUT_NOT_REJECTED"); };
        reject([]{Cipher x("invalid");});
        reject([]{Cipher x("00000000-0000-0000-0000-000000000000");});
        for(const auto& spec:cipher_specs) {
            Cipher cipher(spec.id);
            reject([&]{cipher.encrypt("");});
            reject([&]{cipher.encrypt(std::string("x\0y",3));});
            reject([&]{cipher.decrypt("0");});
            reject([&]{cipher.decrypt(std::string(spec.block*2,'X'));});
            for(auto size:{1u,3u,4u,7u,8u,15u,16u,17u,31u,32u,33u,127u}) {
                std::string plain(size,'a');
                auto encoded=cipher.encrypt(plain);
                auto decoded=cipher.decrypt(encoded);
                ++tests;
                if(decoded!=plain) throw std::runtime_error(std::string("REFERENCE_ROUNDTRIP_MISMATCH ")+spec.id+" length="+std::to_string(size));
            }
        }
        std::string id = "07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA";
        std::vector<std::uint8_t> bytes={0,0,0,0,36}; bytes.insert(bytes.end(),id.begin(),id.end());
        ++tests; if(module_candidate(bytes)!=id) throw std::runtime_error("MODULE_CANDIDATE_MISMATCH");
        bytes.pop_back(); reject([&]{module_candidate(bytes);});
        reject([&]{module_candidate(std::vector<std::uint8_t>(1024*1024+1));});
        std::ifstream vectors(REFERENCE_VECTORS);if(!vectors)throw std::runtime_error("REFERENCE_VECTORS_MISSING");
        std::string row;unsigned rows=0;
        while(std::getline(vectors,row)) {
            if(!row.empty()&&row.back()=='\r')row.pop_back();
            std::istringstream line(row);std::string name,count,expected;
            if(!std::getline(line,name,'\t')||!std::getline(line,count,'\t')||!std::getline(line,expected))throw std::runtime_error("REFERENCE_VECTOR_INVALID");
            Cipher cipher(name);if(cipher.encrypt(std::string(std::stoul(count),'a'))!=expected)throw std::runtime_error("REFERENCE_VECTOR_MISMATCH");++rows;++tests;
        }
        if(rows!=475)throw std::runtime_error("REFERENCE_VECTOR_COUNT");
        std::cout<<tests<<" checks passed across 95 reference branches including 475 pre-patch vectors; NOT native/server equivalence\n";
        return 0;
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 1; }
}
