#include "esurfing/log.hpp"
#include "esurfing/link.hpp"
#include <fstream>
#include <future>
#include <iostream>
#include <map>
#include <stdexcept>
#include <vector>

using namespace esurfing;
namespace fs=std::filesystem;
namespace {
fs::file_time_type file_time(std::chrono::system_clock::time_point time){
#ifdef _WIN32
    return std::chrono::clock_cast<fs::file_time_type::clock>(time);
#else
    return fs::file_time_type::clock::from_sys(time);
#endif
}
unsigned checks=0;
void check(bool ok,const char* what){if(!ok)throw std::runtime_error(what);++checks;}
template<class F> void rejects(F f,const char* what){bool rejected=false;try{f();}catch(const std::exception&){rejected=true;}check(rejected,what);}
std::string read(const fs::path& path){std::ifstream file(path,std::ios::binary);return {std::istreambuf_iterator<char>(file),{}};}
void write(const fs::path& path,const std::string& data){std::ofstream file(path,std::ios::binary);file<<data;file.close();check(static_cast<bool>(file),"fixture write failed");}
std::string day(std::chrono::system_clock::time_point now){auto t=std::chrono::system_clock::to_time_t(now);std::tm tm{};
#ifdef _WIN32
    localtime_s(&tm,&t);
#else
    localtime_r(&t,&tm);
#endif
    char text[20]{};std::strftime(text,sizeof(text),"%Y-%m-%d",&tm);return text;
}
}
int main(){
    auto root=fs::temp_directory_path()/("native-log-test-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try{
        fs::create_directories(root);
        std::map<std::string,std::string> env;
        auto get=[&](const char* key){return env[key];};
        auto defaults=LogConfig::read(root,get);
        check(defaults.directory==root/"logs"&&defaults.file_bytes==10485760&&defaults.total_bytes==52428800&&defaults.rotated==2&&defaults.retention_days==3,"legacy defaults");
        for(auto [key,value]:std::vector<std::pair<std::string,std::string>>{{"LOG_FILE_MAX_BYTES","1023"},{"LOG_FILE_MAX_BYTES","104857601"},{"LOG_FILE_MAX_BYTES","01024"},{"LOG_FILE_MAX_BYTES","+1024"},{"LOG_FILE_MAX_BYTES"," 1024"},{"LOG_TOTAL_MAX_BYTES","1024"},{"LOG_TOTAL_MAX_BYTES","1073741825"},{"LOG_ROTATED_FILES","0"},{"LOG_ROTATED_FILES","6"},{"LOG_RETENTION_DAYS","-1"},{"LOG_RETENTION_DAYS","36501"},{"LOG_RETENTION_DAYS","10000000000"}}){env.clear();env[key]=value;rejects([&]{LogConfig::read(root,get);},"bad log config accepted");}
        env={{"LOG_DIR",(root/"custom").string()},{"LOG_FILE_MAX_BYTES","1024"},{"LOG_TOTAL_MAX_BYTES","2048"},{"LOG_ROTATED_FILES","1"},{"LOG_RETENTION_DAYS","0"}};
        auto config=LogConfig::read(root,get);check(config.directory==root/"custom"&&config.retention_days==0,"override config");
        auto now=std::chrono::system_clock::now();auto current=config.directory/("dialer-"+day(now)+".log");
        {
            LogFiles files(config);
            rejects([&]{LogFiles duplicate(config);},"log directory shared by two writers");
            files.append(now,std::string(1000,'a'));
            files.append(now,std::string(1000,'b'));
            check(read(current)==std::string(1000,'b')+"\n"&&read(current.string()+".1")==std::string(1000,'a')+"\n","first rotation");
            files.append(now,std::string(1000,'c'));
            check(read(current.string()+".1")==std::string(1000,'b')+"\n"&&!fs::exists(current.string()+".2"),"rotation count");
            files.append(now,std::string(10000,'d'));
            check(fs::file_size(current)<=1024&&read(current).ends_with(" [日志已截断]\n"),"long line bounded");
            rejects([&]{files.maintain(now,"../../dialer-2026-01-01");},"invalid maintenance day accepted");
        }
        {
            LogFiles files(config);files.append(now,"AFTER_REOPEN");check(read(current).find("AFTER_REOPEN")!=std::string::npos,"lock released/reopen");
            write(config.directory/"notes.txt","keep-unrelated");
            write(config.directory/"dialer-2020-01-01.log.extra","keep-unrelated-suffix");
            auto old=config.directory/"dialer-2020-01-01.log";
            write(old,"old");fs::last_write_time(old,file_time(now-std::chrono::hours(25)));
            files.maintain(now,day(now));
            check(!fs::exists(old),"mtime retention zero removes >one day");
            check(read(config.directory/"notes.txt")=="keep-unrelated"&&read(config.directory/"dialer-2020-01-01.log.extra")=="keep-unrelated-suffix","unrelated files preserved");
        }
        // Default retention keeps today and the previous two local calendar dates.
        LogConfig quota{root/"quota",1024,2048,2,3};fs::create_directories(quota.directory);
        auto older=quota.directory/("dialer-"+day(now-std::chrono::hours(48))+".log"),newer=quota.directory/("dialer-"+day(now-std::chrono::hours(24))+".log");
        write(older,std::string(1024,'x'));write(newer,std::string(1024,'y'));
        fs::last_write_time(older,file_time(now-std::chrono::hours(30)));
        fs::last_write_time(newer,file_time(now-std::chrono::hours(20)));
        {
            LogFiles files(quota);files.append(now,"quota");
            check(!fs::exists(older)&&fs::exists(newer),"oldest removed to reserve active full file");
            check(fs::file_size(newer)+fs::file_size(quota.directory/("dialer-"+day(now)+".log"))<=quota.total_bytes,"total bound");
            fs::remove(quota.directory/("dialer-"+day(now)+".log")); // Isolate date retention from the independent size quota.
            fs::last_write_time(newer,file_time(now-std::chrono::hours(71)));
            files.maintain(now+std::chrono::hours(24),day(now+std::chrono::hours(24)));check(fs::exists(newer),"previous two calendar dates retained regardless of mtime");
            fs::last_write_time(newer,file_time(now-std::chrono::hours(72)));
            files.maintain(now+std::chrono::hours(48),day(now+std::chrono::hours(48)));check(!fs::exists(newer),"older calendar date removed regardless of mtime");
            auto oversized=quota.directory/("dialer-"+day(now)+".log.2");
            write(oversized,std::string(4096,'z')+std::string(1024,'t'));
            files.maintain(now,day(now));check(read(oversized)==std::string(1024,'t'),"legacy oversized tail trim");
            auto next=now+std::chrono::hours(24);files.append(next,"NEXT_DAY");
            check(read(quota.directory/("dialer-"+day(next)+".log"))=="NEXT_DAY\n","date change");
        }
#ifndef _WIN32
        // Links and special files must never turn rotation/retention into arbitrary file access.
        LogConfig links{root/"links",1024,2048,2,0};fs::create_directories(links.directory);
        auto outside=root/"sentinel";write(outside,"PRIVATE-SENTINEL");
        auto linked=links.directory/("dialer-"+day(now)+".log");fs::create_symlink(outside,linked);
        {LogFiles files(links);rejects([&]{files.append(now,"must-not-write");},"active symlink followed");}
        check(read(outside)=="PRIVATE-SENTINEL","symlink target preserved");fs::remove(linked);
        fs::create_hard_link(outside,linked);
        {LogFiles files(links);rejects([&]{files.append(now,"must-not-write");},"active hardlink followed");}
        check(read(outside)=="PRIVATE-SENTINEL","hardlink target preserved");fs::remove(linked);
        fs::create_symlink(outside,links.directory/"dialer-2020-01-01.log");
        {LogFiles files(links);files.append(now,std::string(1000,'a'));fs::create_symlink(outside,linked.string()+".2");rejects([&]{files.append(now,std::string(1000,'b'));},"rotation symlink not rejected");check(fs::exists(linked)&&!fs::exists(linked.string()+".1"),"rotation preflight prevents partial mutation");}
        check(read(outside)=="PRIVATE-SENTINEL","archive links preserved");
#endif
        {
            HeartbeatLog heartbeat;auto at=std::chrono::steady_clock::time_point{};
            auto first=heartbeat.success(0,15,at);check(first&&first->successes==1&&!first->recovered,"heartbeat first report at monotonic zero");
            for(auto seconds:{60,1740,1799})check(!heartbeat.success(0,15,at+std::chrono::seconds(seconds)),"no per-keep info flood");
            auto summary=heartbeat.success(0,20,at+std::chrono::minutes(30));check(summary&&summary->successes==4,"thirty-minute inclusive boundary/count");
            check(!heartbeat.success(0,20,at+std::chrono::minutes(31)),"summary window reset");
            summary=heartbeat.success(0,20,at+std::chrono::minutes(60));check(summary&&summary->successes==2,"second summary count");
            auto recovery=heartbeat.success(2,5,at+std::chrono::minutes(90));check(recovery&&recovery->recovered&&recovery->failures==2,"recovery wins on summary boundary");
            check(!heartbeat.success(0,5,at+std::chrono::minutes(91)),"recovery only once");
            summary=heartbeat.success(0,5,at+std::chrono::minutes(120));check(summary&&summary->successes==2&&!summary->recovered,"summary window restarts after recovery");
            heartbeat.reset();first=heartbeat.success(0,10,at);check(first&&first->successes==1,"new session resets logs");
        }
        {
            auto interfaces=root/"interfaces";fs::create_directories(interfaces/"wlan0/wireless");
            {std::ofstream(interfaces/"wlan0/operstate")<<"up";std::ofstream(interfaces/"wlan0/carrier")<<"1";}
            LinkMonitor links(interfaces,{}, {},root/"route-missing");auto first=links.poll();check(first.size()==1&&first[0].find("类型=无线")!=std::string::npos,"wireless interface evidence");
            check(links.poll().empty(),"unchanged interfaces do not spam logs");
            std::ofstream(interfaces/"wlan0/carrier")<<"0";auto down=links.poll();check(down.size()==1&&down[0].find("链路未连接")!=std::string::npos,"link down evidence without attributing WAN role");
            LinkMonitor physical(interfaces,"pppoe-wan","wlan0",root/"route-missing");physical.poll();
            auto context=physical.uplink();
            check(uplink_diagnosis(context,std::chrono::steady_clock::now()).find("WAN_PHYSICAL_LINK_DOWN")!=std::string::npos,"explicit physical WAN with no carrier");
            LinkMonitor logical(interfaces,"wlan0",{},root/"route-missing");logical.poll();
            check(uplink_diagnosis(logical.uplink(),std::chrono::steady_clock::now()).find("WAN_LOGICAL_LINK_DOWN")!=std::string::npos,"logical disconnect must not claim cable failure");
            check(uplink_diagnosis(context,context.observed+Seconds(11)).find("EVIDENCE_STALE")!=std::string::npos,"stale evidence cannot identify WAN failure");
            auto route=root/"route";write(route,"Iface Destination Gateway Flags RefCnt Use Metric Mask\nwlan0 00000000 0100000A 0003 0 0 10 00000000\n");
            LinkMonitor inferred(interfaces,{}, {},route);inferred.poll();auto candidate=inferred.uplink();
            check(!candidate.configured&&candidate.interface_name=="wlan0"&&uplink_diagnosis(candidate,std::chrono::steady_clock::now()).find("UPLINK_LINK_DOWN_SUSPECTED")!=std::string::npos,"default route only establishes candidate not confirmed WAN");
            write(route,"Iface Destination Gateway Flags RefCnt Use Metric Mask\n");inferred.poll();
            check(inferred.uplink().interface_name=="wlan0"&&!inferred.uplink().ipv4_default,"retain last exit candidate when route disappears");
            std::ofstream(interfaces/"wlan0/carrier")<<"1";physical.poll();
            check(uplink_diagnosis(physical.uplink(),std::chrono::steady_clock::now()).find("WAN_LOGICAL_LINK_DOWN")!=std::string::npos,"logical PPP missing is distinct from physical carrier up");
            LinkMonitor physical_only(interfaces,{},"wlan0",root/"route-missing");physical_only.poll();
            check(uplink_diagnosis(physical_only.uplink(),std::chrono::steady_clock::now()).find("UPLINK_REACHABILITY_UNCONFIRMED")!=std::string::npos,"carrier up is not Internet proof");
            rejects([&]{LinkMonitor invalid(interfaces,"../../private");},"invalid interface name accepted");
            std::string captured;
            EventLog log(LogConfig{root/"async"},[&](const std::string& line){captured+=line;});
            log.write(LogEvent::state,"external_online");log.write(LogEvent::auth_failed,"https://secret.example/?ticket=private\nFAKE_EVENT");log.write(LogEvent::login_confirmed);log.write(LogEvent::scheduled_restart);log.write(LogEvent::network_state,"online");
            log.heartbeat(HeartbeatNotice{false,3,0,20});log.heartbeat(HeartbeatNotice{true,1,2,5});
            log.bootstrap(18862,std::string(64,'a'),3);log.bootstrap(18862,"private\nFAKE_EVENT",3);
            log.operation_failure("HTTP_TIMEOUT",RequestDiagnostic{Operation::keep,42,1250,28,0},1,5);
            log.operation_failure("PRIVATE-TICKET-MUST-NOT-LOG",RequestDiagnostic{Operation::login,43,2,0,503});
            auto invalid=RequestDiagnostic{Operation::keep,44,-1,0,0};log.operation_failure("HTTP_TIMEOUT",invalid);
            RestartSnapshot plan;plan.next=1790000000;log.plan(plan);plan.persistence_ok=false;log.plan(plan);
            log.interface_state("[INTERFACE_STATE] 接口=tunl0 类型=未确认 状态=关闭");
            check(log.finish(),"async drain");
            std::string stored;for(auto& p:fs::directory_iterator(root/"async"))if(p.path().extension()==".log")stored+=read(p.path());
            check(captured.find("登录认证成功")!=captured.npos&&captured.find("心跳正常")!=captured.npos,"operator console keeps useful success messages");
            check(captured.find("HTTP_TIMEOUT")!=captured.npos&&captured.find("请求超时")!=captured.npos,"console retains concise reason and error code");
            check(captured.find("requestElapsedMs")==captured.npos&&captured.find("请求编号")==captured.npos&&captured.find("tunl0")==captured.npos&&captured.find(std::string(64,'a'))==captured.npos,"console hides inventory and machine detail");
            check(stored.find("请求编号=42")!=stored.npos&&stored.find("tunl0")!=stored.npos&&stored.find(std::string(64,'a'))!=stored.npos,"file log retains machine evidence");
            check(stored.find("RESTART_PLAN_PERSISTENCE_FAILED")!=stored.npos&&captured.find("保存失败")!=captured.npos,"persistence errors visible in both views");
            for(const auto& value:{captured,stored})check(value.find("PRIVATE-TICKET")==value.npos&&value.find("FAKE_EVENT")==value.npos&&value.find("secret.example")==value.npos,"both outputs redact secrets");

        }
        {
            EventLog log(LogConfig{root/"failure"},[](const std::string&){throw std::runtime_error("synthetic sink failure");});log.write(LogEvent::login_confirmed);check(!log.finish()&&log.failed(),"sink failure surfaced");
        }
        {
            std::promise<void> entered,release;auto ready=release.get_future().share();
            EventLog log(LogConfig{root/"overflow"},[&](const std::string&){entered.set_value();ready.wait();});
            log.write(LogEvent::login_confirmed);check(entered.get_future().wait_for(std::chrono::seconds(3))==std::future_status::ready,"slow sink started");
            // The producer remains usable while the sink is blocked, then fails at the fixed cap.
            for(unsigned i=0;i<128;++i)log.write(LogEvent::state,"online");
            check(!log.failed(),"128 queued records allowed");log.write(LogEvent::state,"online");check(log.failed(),"overflow bounded/fatal");release.set_value();check(!log.finish(),"overflow exit status");
        }
        fs::remove_all(root);std::cout<<checks<<" file logging/config/async checks passed\n";return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<"\n";std::error_code ec;fs::remove_all(root,ec);return 1;}
}
