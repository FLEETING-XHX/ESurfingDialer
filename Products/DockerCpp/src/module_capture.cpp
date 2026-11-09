#include "esurfing/module_loader.hpp"
#include "esurfing/state_json.hpp"
#include <atomic>
#ifndef _WIN32
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#endif

namespace esurfing {
void capture_bootstrap(const std::filesystem::path& directory,unsigned slot,
                       std::span<const std::uint8_t> module,const ModuleSummary& summary){
    namespace fs=std::filesystem;
    auto require=[](bool ok){if(!ok)throw ModuleError("BOOTSTRAP_CAPTURE_FAILED");};
    require(slot>=1&&slot<=3&&!module.empty()&&module.size()<=1024*1024);
    auto actual=summarize_module(module);
    require(actual.bytes==summary.bytes&&actual.sha256==summary.sha256&&actual.encoding==summary.encoding);
#ifdef _WIN32
    // 生产为Linux容器；不以继承ACL冒充私有文件权限。
    throw ModuleError("BOOTSTRAP_CAPTURE_UNAVAILABLE");
#else
    auto status=fs::symlink_status(directory);
    if(status.type()==fs::file_type::not_found)require(fs::create_directory(directory));
    require(fs::is_directory(fs::symlink_status(directory))&&!fs::is_symlink(fs::symlink_status(directory)));
    fs::permissions(directory,fs::perms::owner_all,fs::perm_options::replace);
    static std::atomic<unsigned long> sequence=0;
    auto save=[&](const fs::path& path,std::string_view data){
        auto check=[&]{auto s=fs::symlink_status(path);require(s.type()==fs::file_type::not_found||(fs::is_regular_file(s)&&fs::hard_link_count(path)==1));};
        check();auto temporary=path;temporary+=".tmp-"+std::to_string(getpid())+"-"+std::to_string(++sequence);
        int fd=open(temporary.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);require(fd>=0);
        try{
            std::size_t offset=0;
            while(offset<data.size()){
                auto count=::write(fd,data.data()+offset,data.size()-offset);
                if(count<0&&errno==EINTR)continue;
                require(count>0);offset+=static_cast<std::size_t>(count);
            }
            auto closed=close(fd);fd=-1;require(closed==0);check();fs::rename(temporary,path);
        }catch(...){if(fd>=0)close(fd);std::error_code ignored;fs::remove(temporary,ignored);throw;}
    };
    auto prefix=directory/("bootstrap-"+std::to_string(slot));
    save(prefix.string()+".bin",std::string_view(reinterpret_cast<const char*>(module.data()),module.size()));
    save(prefix.string()+".json",encode_state_json({{"bytes",std::int64_t(summary.bytes)},{"sha256",summary.sha256},{"encoding",std::int64_t(summary.encoding)},{"containsRawModule",true},{"loginPayloadCaptured",false}}));
#endif
}
}
