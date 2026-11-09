#include "esurfing/module_loader.hpp"
#include "esurfing/state_json.hpp"
#include <fstream>
#include <iostream>
#include <random>

using namespace esurfing;
namespace {
unsigned checks=0;
void check(bool ok){++checks;if(!ok)throw std::runtime_error("MODULE_CAPTURE_REGRESSION_FAILED");}
template<class F>void rejects(F f){try{f();}catch(const std::exception&){++checks;return;}throw std::runtime_error("MODULE_CAPTURE_REJECTION_EXPECTED");}
}
int main(){try{
    std::vector<std::uint8_t> module(256);module[0]='0';module[1]='0';module[2]='1';module[4]=36;
    const std::string id="11111111-1111-4111-8111-111111111111";
    std::copy(id.begin(),id.end(),module.begin()+5);module[49]=0x30;
    auto summary=summarize_module(module);check(summary.encoding==3&&summary.bytes==256&&summary.sha256.size()==64);
    for(unsigned type=0;type<16;++type){module[49]=std::uint8_t(type<<4);check(summarize_module(module).encoding==int(type));}
    check(summarize_module(std::vector<std::uint8_t>(4)).encoding==-1);
    module[49]=0x30;summary=summarize_module(module);
    namespace fs=std::filesystem;
    auto root=fs::temp_directory_path()/("esurfing-module-capture-"+std::to_string(std::random_device{}()));
    check(fs::create_directory(root));
    struct Cleanup{fs::path root;~Cleanup(){std::error_code ignored;fs::remove_all(root,ignored);}}cleanup{root};
    auto directory=root/"diagnostics";
    capture_bootstrap(directory,1,module,summary);
    auto binary=read_state_file(directory/"bootstrap-1.bin");
    check(binary==std::string(reinterpret_cast<const char*>(module.data()),module.size()));
    auto report=parse_state_json(read_state_file(directory/"bootstrap-1.json"));
    check(std::get<std::string>(report.at("sha256"))==summary.sha256&&std::get<std::int64_t>(report.at("encoding"))==3);
    check((fs::status(directory/"bootstrap-1.bin").permissions()&fs::perms::all)==(fs::perms::owner_read|fs::perms::owner_write));
    check((fs::status(directory).permissions()&fs::perms::all)==fs::perms::owner_all);
    for(unsigned slot=1;slot<=3;++slot)capture_bootstrap(directory,slot,module,summary);
    check(std::distance(fs::directory_iterator(directory),fs::directory_iterator())==6);
    rejects([&]{capture_bootstrap(directory,4,module,summary);});
    auto changed=summary;changed.sha256[0]=changed.sha256[0]=='0'?'1':'0';
    rejects([&]{capture_bootstrap(directory,1,module,changed);});
    auto sentinel=root/"sentinel";std::ofstream(sentinel)<<"KEEP";
    fs::remove(directory/"bootstrap-1.bin");fs::create_symlink(sentinel,directory/"bootstrap-1.bin");
    rejects([&]{capture_bootstrap(directory,1,module,summary);});check(read_state_file(sentinel)=="KEEP");
    auto linked=root/"linked";fs::create_directory_symlink(directory,linked);
    rejects([&]{capture_bootstrap(linked,1,module,summary);});
    std::cout<<checks<<" bootstrap capture checks passed; synthetic bytes, no HTTP/login\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
