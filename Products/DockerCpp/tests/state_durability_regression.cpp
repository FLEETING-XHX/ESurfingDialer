#include "esurfing/state_json.hpp"
#include "esurfing/planner.hpp"
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
#include <iostream>
#include <vector>
using namespace esurfing;
namespace {int mode=0;std::vector<int> events;
void check(bool value,const char* reason){if(!value)throw std::runtime_error(reason);}}
extern "C" int __real_fsync(int);
extern "C" int __real_rename(const char*,const char*);
extern "C" int __wrap_fsync(int fd){
    struct stat status{};if(fstat(fd,&status))return -1;bool directory=S_ISDIR(status.st_mode);
    if(mode){events.push_back(directory?3:1);if((mode==1&&!directory)||(mode==2&&directory)){errno=EIO;return -1;}}
    return __real_fsync(fd);
}
extern "C" int __wrap_rename(const char* a,const char* b){if(mode)events.push_back(2);return __real_rename(a,b);}
int main(){auto dir=std::filesystem::temp_directory_path()/("native-durability-"+std::to_string(getpid()));
try{
    std::filesystem::create_directory(dir);auto path=dir/"state.json";write_state_file(path,"old");
    mode=3;events.clear();write_state_file(path,"new");check(events==std::vector<int>({1,2,3}),"file sync, rename, directory sync ordering");
    mode=1;events.clear();bool failed=false;try{write_state_file(path,"must-not-replace");}catch(const std::exception&){failed=true;}
    check(failed&&read_state_file(path)=="new"&&events==std::vector<int>({1}),"file sync failure preserves old state");
    mode=2;events.clear();failed=false;try{write_state_file(path,"visible-but-not-durable");}catch(const std::exception&){failed=true;}
    check(failed&&read_state_file(path)=="visible-but-not-durable"&&events==std::vector<int>({1,2,3}),"directory sync failure must be propagated after rename");
    RestartPlanner planner(dir/"daily.json",true,4,6,Mono{});
    check(!planner.claim(1790366400,Mono{}+Seconds(30))&&!planner.snapshot(1790366400).persistence_ok,"failed durable claim cannot request restart");
    for(const auto& file:std::filesystem::directory_iterator(dir))check(file.path().extension()!=".tmp","temporary file residue");
    mode=0;std::filesystem::remove_all(dir);std::cout<<"durable ordering and injected file/directory fsync failures passed\n";return 0;
}catch(const std::exception& e){mode=0;std::filesystem::remove_all(dir);std::cerr<<e.what()<<'\n';return 1;}}
