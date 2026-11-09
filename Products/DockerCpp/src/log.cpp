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
std::string field(const std::string& text,const std::string& key){
    auto at=text.find(key);if(at==text.npos)return {};at+=key.size();return text.substr(at,text.find(' ',at)-at);
}
std::string console_log(const std::string& text){
    auto is=[&](const char* code){return text==code||text.starts_with(std::string(code)+" ");};
    if(is("LOGIN_CONFIRMED"))return "登录认证成功";
    if(is("HEARTBEAT_OK"))return "心跳正常（成功"+field(text,"successes=")+"次），下次"+field(text,"nextRetry=")+"后";
    if(is("HEARTBEAT_RECOVERED"))return "心跳已恢复（此前失败"+field(text,"failures=")+"次）";
    if(is("SCHEDULED_RESTART_TRIGGERED"))return "每日维护重启，正在退出 [SCHEDULED_RESTART_TRIGGERED]";
    if(is("NATIVE_STOPPED"))return "认证核心已停止 [NATIVE_STOPPED]";
    if(is("STOP_REQUESTED"))return "收到停止请求，正在退出";
    if(is("TERM_REQUEST_FAILED"))return "下线请求未完成，继续退出";
    if(is("NETWORK_MONITOR_FAILED")||is("NATIVE_CORE_FAILED"))return "核心运行异常，请查看文件日志";
    if(is("UNCLEAN_PREVIOUS_EXIT"))return "上次运行异常结束，请检查容器退出记录";
    if(is("RESTART_PLAN_PERSISTENCE_FAILED"))return "每日重启记录保存失败，已暂停计划重启";
    if(is("RESTART_PLAN")){
        if(field(text,"enabled=")=="0")return "每日维护重启已关闭";
        auto start=field(text,"windowStartHour="),end=field(text,"windowEndHour=");
        if(start.size()==1)start="0"+start;if(end.size()==1)end="0"+end;
        return "每日维护：东八区"+start+":00至"+end+":00内重启一次";
    }
    if(is("STATE paused"))return "认证重试已暂停，请检查配置和文件日志";
    if(is("STATE external_online"))return "外网已连通（当前进程尚未认证）";
    if(is("NETWORK_STATE portal"))return "网络需要认证";
    if(is("OPERATION_FAILED")){
        auto stage=field(text,"stage="),code=field(text,"code=");
        auto result=std::string(stage=="keep"?"心跳":stage=="login"?"登录":stage=="probe"?"联网检测":stage=="bootstrap"?"认证模块":"认证请求")+"："+diagnostic_reason(code)+"（"+code+"）";
        auto status=field(text,"http=");if(!status.empty()&&status!="0")result+="，HTTP "+status;
        auto delay=field(text,"retryDelaySeconds=");if(!delay.empty()&&delay!="0")result+="，"+delay+"秒后重试";
        return result;
    }
    const std::pair<const char*,const char*> links[]={
        {"[WAN_PHYSICAL_LINK_DOWN]","WAN链路断开，请检查网线或上游端口"},
        {"[WAN_LOGICAL_LINK_DOWN]","WAN接口未连接，请检查拨号和接口状态"},
        {"[WAN_INTERFACE_MISSING]","指定的WAN接口不存在，请检查配置"},
        {"[UPLINK_LINK_DOWN_SUSPECTED]","疑似出口链路断开，尚未确认WAN接口"},
        {"[UPLINK_REACHABILITY_UNCONFIRMED]","出口链路已连接，联网检测失败"},
        {"[UPLINK_CAUSE_UNKNOWN]","联网检测失败，原因未确认"},
        {"[UPLINK_EVIDENCE_STALE]","联网检测失败，接口信息不足"},
        {"[UPSTREAM_NETWORK_SUSPECTED]","疑似上游网络或探测端点异常"},
        {"[PROBE_RESPONSE_ABNORMAL]","探测响应异常，暂不能确认外网状态"},
        {"[IPV4_DEFAULT_ROUTE_MISSING]","未发现IPv4默认路由，请检查拨号或路由配置"}};
    for(auto [code,message]:links)if(text.find(code)!=text.npos)return std::string(message)+" "+code;
    // Full interface inventory, hashes, request counters and state transitions
    // remain in the file log. They do not belong in the operator console.
    return {};
}
std::string chinese_log(std::string text){
    // Only render our own bounded messages, never server text or exception bodies.
    const std::pair<const char*,const char*> events[]={
        {"NATIVE_CORE_FAILED","核心运行或状态上报异常，正在退出"},{"STOP_REQUESTED","收到停止信号，正在退出"},{"STATE","运行状态"},{"NETWORK_STATE","联网状态"},{"LOGIN_CONFIRMED","登录认证成功"},
        {"HEARTBEAT_FAILED","心跳失败"},{"AUTH_FAILED","认证失败"},{"SCHEDULED_RESTART_TRIGGERED","每日计划重启已触发，正在退出"},
        {"TERM_REQUEST_SENT_UNCONFIRMED","已发送下线请求，服务端结果未确认"},{"TERM_REQUEST_FAILED","下线请求失败"},
        {"NETWORK_MONITOR_FAILED","网络检测线程异常"},{"NATIVE_STOPPED","认证核心已停止"},
        {"BOOTSTRAP_CAPTURED","认证模块已采集"},{"BOOTSTRAP_CAPTURE_FAILED","认证模块采集失败"},
        {"HEARTBEAT_RECOVERED","心跳已恢复"},{"HEARTBEAT_OK","心跳成功"},{"OPERATION_FAILED","操作失败"},
        {"RESTART_PLAN","每日重启计划"},{"RESTART_PLAN_PERSISTENCE_FAILED","每日重启记录保存失败，已暂停计划重启"},
        {"BOOTSTRAP_RECEIVED","已收到认证模块"},{"INTERFACE_STATE","网络接口状态"},
        {"INTERFACE_UNAVAILABLE","无法读取网络接口状态"},{"UNCLEAN_PREVIOUS_EXIT","上次运行未正常结束，需检查容器退出记录或宿主机状态"}};
    for(auto [code,label]:events){auto n=std::char_traits<char>::length(code);if(text==code||text.starts_with(std::string(code)+" ")){text=std::string(label)+" ["+code+"]"+text.substr(n);break;}}
    const std::pair<const char*,const char*> words[]={
        {"stage=probe","阶段=联网检测"},{"stage=discovery","阶段=认证地址发现"},{"stage=bootstrap","阶段=模块获取"},
        {"stage=ticket","阶段=票据获取"},{"stage=login","阶段=登录"},{"stage=keep","阶段=心跳"},{"stage=term","阶段=下线"},{"stage=unknown","阶段=未知"},
        {"requestElapsedMs=","请求耗时毫秒="},{"retryDelaySeconds=","重试等待秒="},{"retryCount=","重试次数="},
        {"lastRestartEpoch=","上次触发时间戳="},{"windowStartHour=","东八区窗口开始小时="},{"windowEndHour=","窗口结束小时="},
        {"persistenceOk=1","记录保存=正常"},{"persistenceOk=0","记录保存=失败"},{"nextEpoch=","下次计划时间戳="},
        {"enabled=1","启用=是"},{"enabled=0","启用=否"},{"successes=","成功次数="},{"failures=","此前连续失败次数="},
        {"nextRetry=","下次心跳间隔="},{"attempt=","请求编号="},{"curl=","传输返回码="},{"http=","HTTP状态="},
        {"cause=unknown","根因=未确认"},{"code=","错误码="},{"domain=","故障类别="},{"reason=","说明="},
        {"bytes=","字节数="},{"encoding=","模块编码="},
        {" external_online"," 外网已通（本进程尚未认证）"},{" waiting_network"," 等待网络"},{" authorizing"," 正在认证"},
        {" confirming"," 正在确认认证结果"},{" backoff"," 等待重试"},{" paused"," 已暂停认证重试"},{" stopped"," 已停止"},
        {" online"," 已联网"},{" portal"," 需要认证"},{" unreachable"," 联网检测失败，外网可达性未确认"}};
    for(auto [from,to]:words){std::size_t at=0;while((at=text.find(from,at))!=text.npos){text.replace(at,std::char_traits<char>::length(from),to);at+=std::char_traits<char>::length(to);}}
    return text;
}
const char* event_name(LogEvent event){
    switch(event){
    case LogEvent::core_failure:return "NATIVE_CORE_FAILED";
    case LogEvent::stop_requested:return "STOP_REQUESTED";
    case LogEvent::unclean_previous_exit:return "UNCLEAN_PREVIOUS_EXIT";
    case LogEvent::state:return "STATE";
    case LogEvent::network_state:return "NETWORK_STATE";
    case LogEvent::login_confirmed:return "LOGIN_CONFIRMED";
    case LogEvent::heartbeat_failed:return "HEARTBEAT_FAILED";
    case LogEvent::auth_failed:return "AUTH_FAILED";
    case LogEvent::scheduled_restart:return "SCHEDULED_RESTART_TRIGGERED";
    case LogEvent::term_sent:return "TERM_REQUEST_SENT_UNCONFIRMED";
    case LogEvent::term_failed:return "TERM_REQUEST_FAILED";
    case LogEvent::monitor_failed:return "NETWORK_MONITOR_FAILED";
    case LogEvent::stopped:return "NATIVE_STOPPED";
    case LogEvent::module_captured:return "BOOTSTRAP_CAPTURED";
    case LogEvent::module_capture_failed:return "BOOTSTRAP_CAPTURE_FAILED";
    }return "LOG_EVENT_INVALID";
}
}
void log_emergency(const std::string& code)noexcept{
    try{
        auto line=code.size()<=80&&code.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789")==code.npos?std::string("核心或运行环境异常 [")+code+"]："+diagnostic_reason(code)+"\n":std::string("核心运行异常 [NATIVE_OPERATION_FAILED]\n");
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
void LogFiles::maintain(std::chrono::system_clock::time_point /*now*/,const std::string& day){
    const auto active=config_.directory/("dialer-"+day+".log");
    if(day.size()!=10||!log_name(active.filename().string()))invalid();
    check(active);
    struct Entry{fs::path path;fs::file_time_type time;std::uintmax_t size;};std::vector<Entry> entries;
    std::uintmax_t others=0;
    auto calendar=[](const std::string& value){
        auto date=std::chrono::year_month_day{std::chrono::year{std::stoi(value.substr(0,4))},
            std::chrono::month{static_cast<unsigned>(std::stoi(value.substr(5,2)))},
            std::chrono::day{static_cast<unsigned>(std::stoi(value.substr(8,2)))}};
        return date;
    };
    auto today=calendar(day);if(!today.ok())invalid();
    auto oldest=std::chrono::sys_days(today)-std::chrono::days(std::max(1u,config_.retention_days)-1);
    for(const auto& entry:fs::directory_iterator(config_.directory)){
        if(!log_name(entry.path().filename().string()))continue;
        // Do not follow or delete links, subdirectories, or unrelated files.
        if(!fs::is_regular_file(entry.symlink_status())||fs::hard_link_count(entry.path())!=1)continue;
        auto time=entry.last_write_time();
        auto date=calendar(entry.path().filename().string().substr(7,10));
        if(entry.path()!=active&&date.ok()&&std::chrono::sys_days(date)<oldest){fs::remove(entry.path());continue;}
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
    if(line.size()+1>config_.file_bytes){
        auto end=static_cast<std::size_t>(config_.file_bytes)-24;
        while(end>0&&(static_cast<unsigned char>(line[end])&0xc0)==0x80)--end;
        line=line.substr(0,end)+" [日志已截断]";
    }
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
            if(event==LogEvent::network_state)safe=code=="online"||code=="portal"||code=="unreachable";
            if(event==LogEvent::state)safe=code=="waiting_network"||code=="external_online"||code=="authorizing"||code=="confirming"||code=="online"||code=="backoff"||code=="paused"||code=="stopped";
            text+=' ';text+=safe?code:"LOG_DETAIL_INVALID";
        }
        if(event==LogEvent::auth_failed||event==LogEvent::heartbeat_failed)text+=std::string("：")+diagnostic_reason(code);
        enqueue(std::move(text));
    }catch(...){failed_=true;ready_.notify_one();}
}
void EventLog::interface_state(const std::string& message)noexcept{
    enqueue(message);
}
void EventLog::heartbeat(const HeartbeatNotice& notice)noexcept{
    try{
        if(notice.retry_seconds<1||notice.retry_seconds>600){write(LogEvent::heartbeat_failed,"LOG_DETAIL_INVALID");return;}
        enqueue(std::string(notice.recovered?"HEARTBEAT_RECOVERED failures=":"HEARTBEAT_OK successes=")+std::to_string(notice.recovered?notice.failures:notice.successes)+" nextRetry="+std::to_string(notice.retry_seconds)+"s");
    }catch(...){failed_=true;ready_.notify_one();}
}
void EventLog::operation_failure(std::string_view code,const RequestDiagnostic& detail,unsigned retries,long delay)noexcept{
    try{
        if(detail.elapsed_ms<0||detail.elapsed_ms>600000||detail.transport_code<0||detail.transport_code>999||
           (detail.http_status!=0&&(detail.http_status<100||detail.http_status>599))||retries>100000||delay<0||delay>86400){
            enqueue("OPERATION_FAILED LOG_DETAIL_INVALID");return;
        }
        enqueue(std::string("OPERATION_FAILED stage=")+operation_name(detail.operation)+" code="+diagnostic_code(code)+
            " attempt="+std::to_string(detail.attempt)+" requestElapsedMs="+std::to_string(detail.elapsed_ms)+
            " curl="+std::to_string(detail.transport_code)+" http="+std::to_string(detail.http_status)+
            " retryCount="+std::to_string(retries)+" retryDelaySeconds="+std::to_string(delay)+" domain="+diagnostic_domain(code)+" reason="+diagnostic_reason(code)+" cause=unknown");
    }catch(...){failed_=true;ready_.notify_one();}
}
void EventLog::plan(const RestartSnapshot& plan)noexcept{
    try{
        if(plan.next<0||plan.next>RestartPlanner::max_epoch||plan.last_restart<0||plan.last_restart>RestartPlanner::max_epoch||plan.start_hour>23||plan.end_hour>24||plan.start_hour>=plan.end_hour){enqueue("RESTART_PLAN LOG_DETAIL_INVALID");return;}
        enqueue("RESTART_PLAN enabled="+std::to_string(plan.enabled)+" nextEpoch="+std::to_string(plan.next)+
            " lastRestartEpoch="+std::to_string(plan.last_restart)+" windowStartHour="+std::to_string(plan.start_hour)+
            " windowEndHour="+std::to_string(plan.end_hour)+" persistenceOk="+std::to_string(plan.persistence_ok));
        if(!plan.persistence_ok)enqueue("RESTART_PLAN_PERSISTENCE_FAILED");
    }catch(...){failed_=true;ready_.notify_one();}
}
void EventLog::bootstrap(std::size_t bytes,const std::string& digest,int encoding)noexcept{
    try{
        if(bytes==0||bytes>1024*1024||digest.size()!=64||digest.find_first_not_of("0123456789abcdef")!=digest.npos||encoding< -1||encoding>15){
            write(LogEvent::module_capture_failed,"MODULE_SUMMARY_INVALID");return;
        }
        enqueue("BOOTSTRAP_RECEIVED bytes="+std::to_string(bytes)+" sha256="+digest+" encoding="+std::to_string(encoding));
    }catch(...){failed_=true;ready_.notify_one();}
}
void EventLog::enqueue(std::string text)noexcept{
    try{
        auto display=console_log(text);
        text=chinese_log(std::move(text));
        {std::lock_guard guard(mutex_);if(closing_||failed_)return;if(queue_.size()>=128){failed_=true;queue_.clear();ready_.notify_one();return;}queue_.push_back({std::chrono::system_clock::now(),std::move(text),std::move(display)});}
        ready_.notify_one();
    }catch(...){failed_=true;ready_.notify_one();}
}
void EventLog::run()noexcept{
    try{
        std::string last_plan,last_console;std::chrono::system_clock::time_point last_output{};
        bool network_failed=false;
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
            files_.append(record.time,line);
            auto display=record.console;
            if(record.text.find("[NETWORK_STATE]")!=record.text.npos){
                if(record.text.find("联网检测失败")!=record.text.npos)network_failed=true;
                else if(record.text.find("已联网")!=record.text.npos){if(network_failed)display="网络已恢复";network_failed=false;}
            }
            if(record.text.find("[RESTART_PLAN]")!=record.text.npos){if(display==last_plan)display.clear();else last_plan=display;}
            if(!display.empty()&&(display!=last_console||record.time-last_output>=std::chrono::seconds(5))){
                output_(stamp(record.time,"%Y-%m-%d %H:%M:%S")+" "+display+"\n");last_console=display;last_output=record.time;
            }
            if(std::chrono::steady_clock::now()>=next){auto now=std::chrono::system_clock::now();files_.maintain(now,stamp(now,"%Y-%m-%d"));next=std::chrono::steady_clock::now()+std::chrono::hours(1);}
        }
    }catch(...){failed_=true;}
}
bool EventLog::finish()noexcept{
    {std::lock_guard guard(mutex_);closing_=true;}ready_.notify_one();if(worker_.joinable())worker_.join();return !failed_;
}
}
