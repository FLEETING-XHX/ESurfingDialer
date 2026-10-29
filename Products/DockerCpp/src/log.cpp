#include "esurfing/log.hpp"
#include <algorithm>
#include <array>
#include <charconv>
#include <ctime>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>
#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#else
#include <cerrno>
#include <fcntl.h>
#include <poll.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace esurfing {
namespace {
namespace fs=std::filesystem;
[[noreturn]] void invalid(){throw std::runtime_error("LOG_IO_FAILED");}
// dialer-YYYY-MM-DD.log is 21 bytes; only rotations .1 through .5 are legacy-owned.
bool log_name(const std::string& name){
    if(name.size()!=21&&name.size()!=23)return false;
    if(!name.starts_with("dialer-")||name.substr(17,4)!=".log")return false;
    for(std::size_t i=7;i<17;++i){if(i==11||i==14){if(name[i]!='-')return false;}else if(name[i]<'0'||name[i]>'9')return false;}
    return name.size()==21||(name[21]=='.'&&name[22]>='1'&&name[22]<='5');
}
std::string stamp(std::chrono::system_clock::time_point now, const char* format){
    auto time=std::chrono::system_clock::to_time_t(now);std::tm tm{};
#ifdef _WIN32
    if(localtime_s(&tm,&time))invalid();
#else
    if(!localtime_r(&time,&tm))invalid();
#endif
    char buffer[40]{};if(!std::strftime(buffer,sizeof(buffer),format,&tm))invalid();return buffer;
}
void console(const std::string& line){
#ifdef _WIN32
    std::cout<<line<<std::flush;if(!std::cout)invalid();
#else
    // O_NONBLOCK plus a finite poll budget prevents a full stdout pipe from hanging shutdown.
    auto flags=fcntl(STDOUT_FILENO,F_GETFL);if(flags<0||fcntl(STDOUT_FILENO,F_SETFL,flags|O_NONBLOCK)<0)invalid();
    bool ok=true;std::size_t at=0;auto end=std::chrono::steady_clock::now()+std::chrono::seconds(2);
    while(at<line.size()){
        auto n=::write(STDOUT_FILENO,line.data()+at,line.size()-at);
        if(n>0){at+=static_cast<std::size_t>(n);continue;}
        if(n<0&&errno==EINTR)continue;
        if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK)&&std::chrono::steady_clock::now()<end){pollfd fd{STDOUT_FILENO,POLLOUT,0};poll(&fd,1,50);continue;}
        ok=false;break;
    }
    if(fcntl(STDOUT_FILENO,F_SETFL,flags)<0)ok=false;
    if(!ok)invalid();
#endif
}
const char* event_name(LogEvent event){
    switch(event){
    case LogEvent::state:return "STATE";
    case LogEvent::login_confirmed:return "LOGIN_CONFIRMED";
    case LogEvent::heartbeat_failed:return "HEARTBEAT_FAILED";
    case LogEvent::auth_failed:return "AUTH_FAILED";
    case LogEvent::scheduled_reauth:return "SCHEDULED_REAUTH_TRIGGERED";
    case LogEvent::term_sent:return "TERM_REQUEST_SENT_UNCONFIRMED";
    case LogEvent::term_failed:return "TERM_REQUEST_FAILED";
    case LogEvent::monitor_failed:return "NETWORK_MONITOR_FAILED";
    case LogEvent::stopped:return "NATIVE_STOPPED";
    }return "LOG_EVENT_INVALID";
}
}
void log_emergency(const std::string& code)noexcept{
    try{
        auto line=code.size()<=80&&code.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789")==code.npos?code+"\n":std::string("NATIVE_OPERATION_FAILED\n");
#ifdef _WIN32
        std::cerr<<line;
#else
        auto flags=fcntl(STDERR_FILENO,F_GETFL);
        if(flags<0||fcntl(STDERR_FILENO,F_SETFL,flags|O_NONBLOCK)<0)return;
        // Best effort only: reporting a failed sink must not block on that same full pipe.
        (void)::write(STDERR_FILENO,line.data(),line.size());
        (void)fcntl(STDERR_FILENO,F_SETFL,flags);
#endif
    }catch(...){}
}
std::optional<HeartbeatNotice> HeartbeatLog::success(unsigned failures,long long retry,std::chrono::steady_clock::time_point now){
    if(successes_<std::numeric_limits<std::uint64_t>::max())++successes_;
    if(failures==0&&last_&&now-*last_<std::chrono::minutes(30))return std::nullopt;
    HeartbeatNotice notice{failures>0,successes_,failures,retry};last_=now;successes_=0;return notice;
}
LogConfig LogConfig::read(const fs::path& state,const std::function<std::string(const char*)>& env){
    LogConfig c;auto path=env("LOG_DIR");c.directory=path.empty()?state/"logs":fs::path(path);
    auto number=[&](const char* key,std::uintmax_t fallback,std::uintmax_t low,std::uintmax_t high){
        auto text=env(key);if(text.empty())return fallback;
        std::uintmax_t v=0;auto [p,e]=std::from_chars(text.data(),text.data()+text.size(),v);
        if(text.size()>10||(text.size()>1&&text.front()=='0')||e!=std::errc{}||p!=text.data()+text.size()||v<low||v>high)throw std::runtime_error("LOG_CONFIG_INVALID");
        return v;
    };
    c.file_bytes=number("LOG_FILE_MAX_BYTES",c.file_bytes,1024,104857600);
    c.total_bytes=number("LOG_TOTAL_MAX_BYTES",c.total_bytes,c.file_bytes,1073741824);
    c.rotated=static_cast<unsigned>(number("LOG_ROTATED_FILES",c.rotated,1,5));
    c.retention_days=static_cast<unsigned>(number("LOG_RETENTION_DAYS",c.retention_days,0,36500));
    return c;
}
LogFiles::LogFiles(LogConfig config):config_(std::move(config)){
    if(config_.file_bytes<1024||config_.file_bytes>104857600||config_.total_bytes<config_.file_bytes||config_.total_bytes>1073741824||config_.rotated<1||config_.rotated>5||config_.retention_days>36500)throw std::runtime_error("LOG_CONFIG_INVALID");
    fs::create_directories(config_.directory);
    if(fs::is_symlink(fs::symlink_status(config_.directory)))invalid();
    const auto lock=config_.directory/"native-logs.lock";
    check(lock);
#ifdef _WIN32
    auto h=CreateFileW(lock.c_str(),GENERIC_READ|GENERIC_WRITE,0,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(h==INVALID_HANDLE_VALUE)throw std::runtime_error("LOG_DIRECTORY_OWNED");
    lock_=reinterpret_cast<std::intptr_t>(h);
#else
    auto fd=open(lock.c_str(),O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK,0600);
    if(fd<0)invalid();
    struct stat status{};
    if(fstat(fd,&status)||!S_ISREG(status.st_mode)||status.st_nlink!=1){close(fd);invalid();}
    if(flock(fd,LOCK_EX|LOCK_NB)){close(fd);throw std::runtime_error("LOG_DIRECTORY_OWNED");}lock_=fd;
#endif
}
LogFiles::~LogFiles(){if(lock_==-1)return;
#ifdef _WIN32
    CloseHandle(reinterpret_cast<HANDLE>(lock_));
#else
    close(static_cast<int>(lock_));
#endif
}
void LogFiles::check(const fs::path& path)const{
    auto status=fs::symlink_status(path);
    if(status.type()==fs::file_type::not_found)return;
    if(!fs::is_regular_file(status)||fs::hard_link_count(path)!=1)invalid();
}
void LogFiles::maintain(std::chrono::system_clock::time_point now,const std::string& day){
    const auto active=config_.directory/("dialer-"+day+".log");
    if(day.size()!=10||!log_name(active.filename().string()))invalid();
    check(active);
    struct Entry{fs::path path;fs::file_time_type time;std::uintmax_t size;};std::vector<Entry> entries;
    std::uintmax_t others=0;
    #ifdef _WIN32
    const auto file_now=std::chrono::clock_cast<fs::file_time_type::clock>(now);
#else
    const auto file_now=fs::file_time_type::clock::from_sys(now);
#endif
    for(const auto& entry:fs::directory_iterator(config_.directory)){
        if(!log_name(entry.path().filename().string()))continue;
        // Do not follow or delete links, subdirectories, or unrelated files.
        if(!fs::is_regular_file(entry.symlink_status())||fs::hard_link_count(entry.path())!=1)continue;
        auto time=entry.last_write_time();
        if(entry.path()!=active&&file_now-time>=std::chrono::hours(24LL*(config_.retention_days+1))){fs::remove(entry.path());continue;}
        auto size=entry.file_size();
        if(size>config_.file_bytes){
            // Retain the tail with a fixed 64 KiB buffer, even for oversized legacy files.
            std::fstream io(entry.path(),std::ios::binary|std::ios::in|std::ios::out);io.exceptions(std::ios::badbit|std::ios::failbit);
            std::array<char,65536> buffer{};std::uintmax_t at=0,start=size-config_.file_bytes;
            while(at<config_.file_bytes){auto n=static_cast<std::streamsize>(std::min<std::uintmax_t>(buffer.size(),config_.file_bytes-at));io.seekg(static_cast<std::streamoff>(start+at));io.read(buffer.data(),n);io.seekp(static_cast<std::streamoff>(at));io.write(buffer.data(),n);at+=static_cast<std::uintmax_t>(n);}
            io.close();fs::resize_file(entry.path(),config_.file_bytes);size=config_.file_bytes;
        }
        if(entry.path()!=active){others+=size;entries.push_back({entry.path(),time,size});if(entries.size()>4096)throw std::runtime_error("LOG_DIRECTORY_TOO_LARGE");}
    }
    std::sort(entries.begin(),entries.end(),[](const Entry& a,const Entry& b){return a.time==b.time?a.path<b.path:a.time<b.time;});
    for(const auto& entry:entries){if(others<=config_.total_bytes-config_.file_bytes)break;check(entry.path);fs::remove(entry.path);others-=entry.size;}
}
void LogFiles::rotate(const fs::path& active){
    // Preflight the entire chain before any deletion or move.
    for(unsigned i=1;i<=config_.rotated;++i)check(active.string()+"."+std::to_string(i));
    fs::remove(active.string()+"."+std::to_string(config_.rotated));
    for(unsigned i=config_.rotated;i>1;--i){auto source=fs::path(active.string()+"."+std::to_string(i-1));if(fs::exists(source))fs::rename(source,active.string()+"."+std::to_string(i));}
    if(fs::exists(active))fs::rename(active,active.string()+".1");
}
void LogFiles::append(std::chrono::system_clock::time_point now,const std::string& value){
    auto day=stamp(now,"%Y-%m-%d");auto active=config_.directory/("dialer-"+day+".log");
    if(day!=day_){maintain(now,day);day_=day;}
    check(active);auto line=value;
    if(line.size()+1>config_.file_bytes)line=line.substr(0,static_cast<std::size_t>(config_.file_bytes)-22)+" [log line truncated]";
    line+='\n';auto size=fs::exists(active)?fs::file_size(active):0;
    if(size+line.size()>config_.file_bytes){rotate(active);maintain(now,day);}
    std::ofstream out(active,std::ios::binary|std::ios::app);out.exceptions(std::ios::badbit|std::ios::failbit);out<<line;out.close();
}
EventLog::EventLog(LogConfig config,std::function<void(const std::string&)> output):files_(std::move(config)),output_(output?std::move(output):console),worker_([this]{run();}){}
EventLog::~EventLog(){finish();}
void EventLog::write(LogEvent event,const std::string& code)noexcept{
    try{
        std::string text=event_name(event);
        if(!code.empty()){
            // Only state/code tokens, never exception text, XML, credentials or URLs.
            bool safe=code.size()<=80&&code.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789")==code.npos;
            if(event==LogEvent::state)safe=code=="waiting_network"||code=="external_online"||code=="authorizing"||code=="confirming"||code=="online"||code=="backoff"||code=="paused"||code=="stopped";
            text+=' ';text+=safe?code:"LOG_DETAIL_INVALID";
        }
        enqueue(std::move(text));
    }catch(...){failed_=true;ready_.notify_one();}
}
void EventLog::heartbeat(const HeartbeatNotice& notice)noexcept{
    try{
        if(notice.retry_seconds<1||notice.retry_seconds>600){write(LogEvent::heartbeat_failed,"LOG_DETAIL_INVALID");return;}
        enqueue(std::string(notice.recovered?"HEARTBEAT_RECOVERED failures=":"HEARTBEAT_SUMMARY successes=")+std::to_string(notice.recovered?notice.failures:notice.successes)+" nextRetry="+std::to_string(notice.retry_seconds)+"s");
    }catch(...){failed_=true;ready_.notify_one();}
}
void EventLog::enqueue(std::string text)noexcept{
    try{
        {std::lock_guard guard(mutex_);if(closing_||failed_)return;if(queue_.size()>=128){failed_=true;queue_.clear();ready_.notify_one();return;}queue_.push_back({std::chrono::system_clock::now(),std::move(text)});}
        ready_.notify_one();
    }catch(...){failed_=true;ready_.notify_one();}
}
void EventLog::run()noexcept{
    try{
        auto next=std::chrono::steady_clock::now();
        while(true){
            std::unique_lock lock(mutex_);ready_.wait_until(lock,next,[&]{return closing_||failed_||!queue_.empty();});
            if(failed_)return;
            if(queue_.empty()){
                if(closing_)return;
                lock.unlock();auto now=std::chrono::system_clock::now();files_.maintain(now,stamp(now,"%Y-%m-%d"));next=std::chrono::steady_clock::now()+std::chrono::hours(1);continue;
            }
            auto record=std::move(queue_.front());queue_.pop_front();lock.unlock();
            auto line=stamp(record.time,"%Y-%m-%d %H:%M:%S")+" "+record.text;
            files_.append(record.time,line);output_(line+"\n");
            if(std::chrono::steady_clock::now()>=next){auto now=std::chrono::system_clock::now();files_.maintain(now,stamp(now,"%Y-%m-%d"));next=std::chrono::steady_clock::now()+std::chrono::hours(1);}
        }
    }catch(...){failed_=true;}
}
bool EventLog::finish()noexcept{
    {std::lock_guard guard(mutex_);closing_=true;}ready_.notify_one();if(worker_.joinable())worker_.join();return !failed_;
}
}
