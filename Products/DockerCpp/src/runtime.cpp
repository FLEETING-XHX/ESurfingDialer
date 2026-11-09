#include "esurfing/session.hpp"
#ifdef ESURFING_RUNTIME_FIXTURE
#include "../tests/reference_session_fixture.hpp"
#endif
#include "esurfing/state_store.hpp"
#include "esurfing/state_json.hpp"
#include "esurfing/config.hpp"
#include "esurfing/identity.hpp"
#include "esurfing/network_monitor.hpp"
#include "esurfing/portal_policy.hpp"
#include "esurfing/log.hpp"
#include "esurfing/link.hpp"
#include "esurfing/module_loader.hpp"
#include <atomic>
#include <csignal>
#include <cstdlib>
#include <ctime>
#include <fstream>
#include <iostream>
#include <mutex>
#include <random>
#include <thread>
#include <charconv>

using namespace esurfing;
static_assert(sizeof(std::time_t)>=8,"Native runtime requires a 64-bit time_t ABI");
namespace {
#ifdef ESURFING_RUNTIME_FIXTURE
constexpr bool fixture_transport=true;
#else
constexpr bool fixture_transport=false;
#endif
volatile std::sig_atomic_t interrupted=0;
void interrupt(int){interrupted=1;}
std::string env(const char* name,const char* fallback=""){auto p=std::getenv(name);return p?p:fallback;}
std::string local_time(){std::time_t t=std::time(nullptr);std::tm tm{};
#ifdef _WIN32
    if(localtime_s(&tm,&t))throw Failure("LOCAL_TIME_INVALID");
#else
    if(!localtime_r(&t,&tm))throw Failure("LOCAL_TIME_INVALID");
#endif
    char result[32]{};if(!std::strftime(result,sizeof(result),"%Y-%m-%d %H:%M:%S",&tm))throw Failure("LOCAL_TIME_INVALID");return result;
}
std::string password(){auto file=env("DIALER_PASSWORD_FILE");auto value=file.empty()?env("DIALER_PASSWORD"):read_password_file(file);if(value.size()>4096)throw Failure("PASSWORD_TOO_LARGE");return value;}
std::int64_t epoch(){
#ifdef ESURFING_RUNTIME_FIXTURE
    // Only the separately compiled test executable accepts a synthetic clock.
    auto path=env("ESURFING_FIXTURE_EPOCH_FILE");
    if(!path.empty()){
        auto text=read_state_file(path);std::int64_t value=0;
        auto [end,error]=std::from_chars(text.data(),text.data()+text.size(),value);
        if(error!=std::errc{}||end!=text.data()+text.size()||value<1||value>RestartPlanner::max_epoch-2*86400)throw Failure("FIXTURE_CLOCK_INVALID");
        return value;
    }
#endif
    return static_cast<std::int64_t>(std::time(nullptr));
}
const char* startup_code(const std::exception& error){
    const std::string code=error.what();
    for(const auto* known:{"STATE_DIRECTORY_OWNED","STATE_LOCK_OPEN_FAILED","DEVICE_IDENTITY_INVALID","STATE_JSON_INVALID","STATE_FILE_INVALID","STATE_FILE_UNREADABLE","STATE_FILE_TOO_LARGE","STATE_REPLACE_FAILED","STATE_SYNC_FAILED","PLANNER_TIME_INVALID","RESTART_WINDOW_INVALID","WAN_INTERFACE_INVALID","PASSWORD_FILE_UNREADABLE","PASSWORD_TOO_LARGE","LOG_CONFIG_INVALID","LOG_IO_FAILED","LOG_DIRECTORY_OWNED"})if(code==known)return known;
    return "NATIVE_STARTUP_FAILED";
}
std::string network_key(const Probe& p){
    try{
        if(auto endpoints=portal_config(p.portal_body,fixture_transport))return endpoints->user_ip+"|"+endpoints->ac_ip;
        auto q=query_parameters(p.portal_url);
        if(q["wlanuserip"].size()==1&&q["wlanacip"].size()==1&&ipv4(q["wlanuserip"][0])&&ipv4(q["wlanacip"][0]))return q["wlanuserip"][0]+"|"+q["wlanacip"][0];
    }catch(const std::exception&){}
    return {};
}
}
int run_native(){
#ifdef ESURFING_EXPERIMENTAL
    if(env("NATIVE_EXPERIMENTAL")!="1") {std::cerr<<"实验版需要明确启用 [NATIVE_EXPERIMENTAL_REQUIRED]：请设置 NATIVE_EXPERIMENTAL=1\n";return 78;}
#endif
    try {
        const auto directory=std::filesystem::path(env("STATE_DIR","/data"));
        auto config=RuntimeConfig::read([](const char* name){return env(name);});
        StateStore store(directory);Core core(config.policy);std::mutex mutex;
        RestartPlanner planner(directory/"daily-restart.json",config.daily_restart_enabled,config.restart_start_hour,config.restart_end_hour);
        auto event=[&](auto fn){std::lock_guard guard(mutex);return fn(core);};
        auto identity=load_identity(directory,[](const char* name){return env(name);});
        const auto& client_id=identity.client_id;const auto& mac=identity.mac;
        const auto user=env("DIALER_USER"),secret=password();
        if(user.empty()||secret.empty())throw Failure("CREDENTIALS_REQUIRED");
#ifndef _WIN32
        std::signal(SIGPIPE,SIG_IGN);
#endif
        EventLog log(LogConfig::read(directory,[](const char* name){return env(name);}));
        try{
            if(std::get<bool>(parse_state_json(read_state_file(directory/"health.json")).at("processAlive")))log.write(LogEvent::unclean_previous_exit);
        }catch(const std::exception&){}

        HeartbeatLog heartbeat_log;
        const auto capture_setting=env("NATIVE_CAPTURE_BOOTSTRAP","0");
        if(capture_setting!="0"&&capture_setting!="1")throw Failure("BOOTSTRAP_CAPTURE_CONFIG_INVALID");
        unsigned capture_slot=0;
        std::signal(SIGINT,interrupt);std::signal(SIGTERM,interrupt);
        std::atomic_bool stop=false,report_failed=false;
        UplinkSnapshot uplink;
        LinkMonitor links("/sys/class/net",env("WAN_INTERFACE"),env("WAN_PHYSICAL_INTERFACE"));
        std::jthread reporter([&](std::stop_token token){
            auto next_report=std::chrono::steady_clock::now();
            auto next_link=next_report;
            std::optional<RestartSnapshot> logged_plan;
            while(!token.stop_requested()) {
                if(interrupted||log.failed())stop=true;
                try{
                    auto now=std::chrono::steady_clock::now();auto wall=epoch();
                    // Independent of HTTP/probe work: request a clean PID-1 exit and
                    // let Docker's unless-stopped policy restart this container.
                    if(!stop.load()&&!interrupted&&planner.claim(wall,now)){
                        log.write(LogEvent::scheduled_restart);stop=true;
                    }
                    if(now>=next_link){for(const auto& message:links.poll())log.interface_state(message);{std::lock_guard guard(mutex);uplink=links.uplink();}next_link=now+Seconds(5);}
                    auto plan=planner.snapshot(wall);
                    if(!logged_plan||*logged_plan!=plan){log.plan(plan);logged_plan=plan;}
                    if(now>=next_report){
                        std::lock_guard guard(mutex);core.tick(now);
                        store.write(core.snapshot(),!stop.load(),wall,config.health_interval.count(),&plan);
                        next_report=now+config.health_interval;
                    }
                }
                catch(...){report_failed=true;stop=true;return;}
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        });
        CurlHttp http(stop,fixture_transport);std::unique_ptr<Session> session;
        auto diagnose=[&](const std::exception& error,RequestDiagnostic context,unsigned retries=0,long delay=0){
            if(auto failure=dynamic_cast<const Failure*>(&error);failure&&failure->diagnostic.attempt)context=failure->diagnostic;
            log.operation_failure(error.what(),context,retries,delay);
        };
        auto urls=probe_urls(env("NETWORK_CHECK_URLS"),fixture_transport);
        if(fixture_transport)for(const auto& url:urls)if(!url.starts_with("http://127.0.0.1:"))throw Failure("FIXTURE_LOOPBACK_REQUIRED");
        NetworkMonitor monitor(config.network_interval,[&](std::atomic_bool& cancelled){CurlHttp transport(cancelled,fixture_transport);return probe(transport,urls,fixture_transport,config.probe_budget);});
        PortalPolicy portal_policy(config);
        std::optional<Probe> pending,recovery_candidate;std::uint64_t observation_sequence=0;
        std::optional<Probe::Kind> logged_network;
        std::string logged_probe_error;
        std::string active_network_key;
        std::uint64_t generation=0;
        auto wait=[&](std::chrono::milliseconds delay){auto until=std::chrono::steady_clock::now()+delay;while(!stop.load()&&std::chrono::steady_clock::now()<until)std::this_thread::sleep_for(std::chrono::milliseconds(100));};
        Phase previous=Phase::stopped;
        while(!stop.load()) {
            if(log.failed()){report_failed=true;stop=true;break;}
            if(monitor.failed()){log.write(LogEvent::monitor_failed);report_failed=true;stop=true;break;}
            auto now=std::chrono::steady_clock::now();event([&](Core& c){c.tick(now);});
            auto state=event([](Core& c){return c.snapshot();});
            if(state.phase!=previous){log.write(LogEvent::state,phase_name(state.phase));previous=state.phase;}
            if(state.phase==Phase::backoff && !pending && recovery_candidate){pending=recovery_candidate;session.reset();}
            try {
                auto latest=monitor.latest();
                if(latest.sequence>observation_sequence){
                    observation_sequence=latest.sequence;const auto& observation=latest.result;
                    if(!logged_network||*logged_network!=observation.kind){
                        log.write(LogEvent::network_state,observation.kind==Probe::online?"online":observation.kind==Probe::portal?"portal":"unreachable");
                        logged_network=observation.kind;
                    }
                    if(observation.kind==Probe::error){
                        auto evidence=event([&](Core&){return uplink;});
                        auto diagnosis=uplink_diagnosis(evidence,std::chrono::steady_clock::now());
                        auto signature=diagnosis;
                        for(const auto& failure:observation.failures)signature+=std::string(failure.code)+std::to_string(failure.diagnostic.http_status);
                        if(logged_probe_error!=signature){
                            log.interface_state(diagnosis);
                            log.interface_state("[PROBE_FAILURE_SUMMARY] 已尝试探测="+std::to_string(observation.attempted)+"，以下最多记录8个失败结果；编号按本轮顺序，不记录地址");
                            if(evidence.route_readable&&!evidence.ipv4_default)log.interface_state("[IPV4_DEFAULT_ROUTE_MISSING] 当前未发现IPv4默认路由；请检查拨号、DHCP及路由配置，IPv6和策略路由需另查");
                            unsigned timeouts=0,abnormal_http=0;
                            for(const auto& failure:observation.failures){
                                std::string_view code=failure.code;
                                if(code=="HTTP_TIMEOUT"||code=="HTTP_CONNECT_FAILED")++timeouts;
                                if(code=="HTTP_STATUS_ERROR"||code=="HTTP_UNEXPECTED_RESPONSE")++abnormal_http;
                            }
                            if(timeouts>=2&&evidence.link==UplinkSnapshot::up&&std::chrono::steady_clock::now()-evidence.observed<=Seconds(10))log.interface_state("[UPSTREAM_NETWORK_SUSPECTED] 多个探测请求超时或连接失败，疑似上游网络或探测端点异常，尚不能确认WAN故障");
                            if(abnormal_http)log.interface_state("[PROBE_RESPONSE_ABNORMAL] 探测响应状态或内容异常，不能直接认定WAN断开或整个外网中断");
                            unsigned index=0;for(const auto& failure:observation.failures){
                                log.interface_state("[PROBE_FAILURE] 本轮探测编号="+std::to_string(++index));
                                log.operation_failure(failure.code,failure.diagnostic);
                            }
                            logged_probe_error=std::move(signature);
                        }
                    }else logged_probe_error.clear();
                    event([&](Core& c){c.network(observation.kind==Probe::online);});
                    if(observation.kind==Probe::portal){
                        recovery_candidate=observation;
                        const auto key=observation.portal_url+"|"+network_key(observation);
                        if(portal_policy.observe(key,state.authenticated,state.heartbeat_failures,latest.observed_at)){
                            pending=observation;
                            if(state.authenticated)portal_policy.requested(latest.observed_at);
                        }
                    }else portal_policy.non_portal();
                    }
                // Pausing authentication does not pause external reachability observations.
                if(state.phase==Phase::paused){pending.reset();session.reset();wait(Seconds(5));continue;}
                if(pending) {
                    auto accepted=event([&](Core& c){return c.authorize(std::chrono::steady_clock::now());});
                    if(accepted) {
                        generation=*accepted;
                        heartbeat_log.reset();
                        if(session){try{session->terminate();}catch(const std::exception& error){diagnose(error,session->last_diagnostic());log.write(LogEvent::term_failed);}}
                        session.reset();
                        auto endpoints=discover(http,*pending,fixture_transport);
                        const auto key=endpoints.user_ip+"|"+endpoints.ac_ip;
                        active_network_key=key;
                        Session::CipherFactory factory;
#ifdef ESURFING_RUNTIME_FIXTURE
                        factory=reference_session_cipher_factory();
#endif
                        session=std::make_unique<Session>(http,std::move(endpoints),Identity{client_id,mac,local_time()},fixture_transport,config.heartbeat_max,std::move(factory));
                        session->on_time(local_time);
                        session->on_bootstrap([&](std::span<const std::uint8_t> module){
                            try{
                                auto summary=summarize_module(module);log.bootstrap(summary.bytes,summary.sha256,summary.encoding);
                                if(capture_setting=="1"){
                                    const auto slot=capture_slot++%3+1;
                                    capture_bootstrap(directory/"diagnostics",slot,module,summary);
                                    log.write(LogEvent::module_captured,"SLOT_"+std::to_string(slot));
                                }
                            }catch(...){log.write(LogEvent::module_capture_failed,"BOOTSTRAP_CAPTURE_FAILED");}
                        });
                        session->on_progress([&]{event([&](Core& c){
                            auto s=c.snapshot();
                            if(s.generation!=generation||(s.phase!=Phase::authorizing&&s.phase!=Phase::confirming&&s.phase!=Phase::online))throw Failure("AUTH_GENERATION_CANCELLED",false);
                            auto observation=monitor.latest();
                            if(observation.result.kind==Probe::portal){auto current_key=network_key(observation.result);if(!current_key.empty()&&current_key!=active_network_key)throw Failure("NETWORK_CANDIDATE_CHANGED",false);}
                            c.progress(generation,std::chrono::steady_clock::now());});});
                        session->initialize();session->login(user,secret);
                        event([&](Core& c){c.login_accepted(generation,std::chrono::steady_clock::now());});
                        bool confirmed=false;
                        for(unsigned attempt=0;attempt<config.confirmation_attempts&&!stop.load();++attempt) {
                            wait(config.confirmation_interval);if(stop.load())break;
                            try {auto interval=session->keep();confirmed=event([&](Core& c){return c.keep_confirmed(generation,interval,std::chrono::steady_clock::now());});if(confirmed)if(auto notice=heartbeat_log.success(0,interval.count(),std::chrono::steady_clock::now()))log.heartbeat(*notice);break;}
                            catch(const std::exception& error){if(attempt+1==config.confirmation_attempts)throw;diagnose(error,session->last_diagnostic(),attempt+1,config.confirmation_interval.count());}
                        }
                        if(confirmed){
                            pending.reset();portal_policy.auth_success(std::chrono::steady_clock::now());log.write(LogEvent::login_confirmed);
                        }
                    }
                } else if(session && event([&](Core& c){return c.heartbeat_due(std::chrono::steady_clock::now());})) {
                    try{auto interval=session->keep();auto failures=event([&](Core& c)->std::optional<unsigned>{auto previous_failures=c.snapshot().heartbeat_failures;if(!c.keep_confirmed(generation,interval,std::chrono::steady_clock::now()))return std::nullopt;return previous_failures;});if(failures)if(auto notice=heartbeat_log.success(*failures,interval.count(),std::chrono::steady_clock::now()))log.heartbeat(*notice);}
                    catch(const std::exception& error){if(stop.load())break;
                        auto failures=event([](Core& c){return c.snapshot().heartbeat_failures+1;});
                        auto code=event([&](Core& c){
                            auto failure=dynamic_cast<const Failure*>(&error);
                            const bool protocol_rejected=failure&&std::string(failure->what())=="HEARTBEAT_PROTOCOL_INVALID";
                            if(failure&&std::string(failure->what())=="NETWORK_CANDIDATE_CHANGED"){
                                c.failed(generation,"NETWORK_CANDIDATE_CHANGED",std::chrono::steady_clock::now());
                            }else{c.heartbeat_failed(generation,std::chrono::steady_clock::now(),protocol_rejected);}
                            return c.snapshot().error;});
                        log.write(LogEvent::heartbeat_failed,code);
                        diagnose(error,session->last_diagnostic(),failures,event([](Core& c){return c.retry_after(std::chrono::steady_clock::now()).count();}));
                        if(!event([](Core& c){return c.snapshot().authenticated;})){session.reset();}}
                }
            }catch(const Failure& error){if(stop.load())break;auto code=event([&](Core& c){c.failed(generation,error.what(),std::chrono::steady_clock::now(),error.deterministic);return c.snapshot().error;});log.write(LogEvent::auth_failed,code);
                diagnose(error,session?session->last_diagnostic():RequestDiagnostic{Operation::discovery},event([](Core& c){return c.snapshot().recovery_attempts;}),event([](Core& c){return c.retry_after(std::chrono::steady_clock::now()).count();}));session.reset();}
             catch(const std::exception& error){if(stop.load())break;event([&](Core& c){c.failed(generation,"NATIVE_OPERATION_FAILED",std::chrono::steady_clock::now(),true);});diagnose(error,session?session->last_diagnostic():RequestDiagnostic{Operation::discovery});session.reset();}
            wait(std::chrono::milliseconds(250));
        }
        if(interrupted)log.write(LogEvent::stop_requested);
        if(report_failed)log.write(LogEvent::core_failure);
        monitor.stop();
        // Old work remains cancelled. A separate transport gets one bounded cleanup attempt.
        event([](Core& c){c.stop();});
        if(session){std::atomic_bool cleanup_stop=false;CurlHttp cleanup(cleanup_stop,fixture_transport);
            try{session->terminate(&cleanup);log.write(LogEvent::term_sent);}catch(const std::exception& error){diagnose(error,session->last_diagnostic());log.write(LogEvent::term_failed);}}
        session.reset();reporter.request_stop();reporter.join();
        auto plan=planner.snapshot(epoch());store.write(core.snapshot(),false,epoch(),config.health_interval.count(),&plan);
        log.write(LogEvent::stopped);
        if(!log.finish()){log_emergency("LOG_SINK_FAILED");return 1;}
        return report_failed?1:0;
    }catch(const Failure& e){std::string code=e.what();if(code.size()>80||code.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789")!=code.npos)code="NATIVE_STARTUP_FAILED";log_emergency(code);return 1;}
     catch(const std::exception& error){log_emergency(startup_code(error));return 1;}
}
