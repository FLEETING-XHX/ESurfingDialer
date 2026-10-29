#include "esurfing/state_store.hpp"
#include <iostream>
#ifdef ESURFING_NETWORK
int run_native();
#endif

int main(int argc, char** argv) {
    if(argc == 2 && std::string(argv[1]) == "--version") {
        std::cout << "ESurfingDialer Native " ESURFING_VERSION " (experimental; native equivalence pending)\n";
        return 0;
    }
    if(argc!=1){
        // Never echo argv: old credential flags and diagnostic URLs can contain secrets.
        std::cerr << "NATIVE_ARGUMENTS_UNSUPPORTED: use environment/file configuration; only --version is supported\n";
        return 64;
    }
#ifdef ESURFING_NETWORK
    if(argc==1) return run_native();
#endif
    std::cerr << "NATIVE_MIGRATION_INCOMPLETE: follow DCPP-2 through DCPP-4 before deployment\n";
    return 78;
}
