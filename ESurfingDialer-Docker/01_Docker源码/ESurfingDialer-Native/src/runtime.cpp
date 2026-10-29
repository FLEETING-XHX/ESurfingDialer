#include "esurfing/session.hpp"
#include "esurfing/state_store.hpp"
#include "esurfing/config.hpp"
#include "esurfing/identity.hpp"
#include "esurfing/network_monitor.hpp"
#include "esurfing/portal_policy.hpp"
#include "esurfing/log.hpp"
#include <atomic>
#include <csignal>
#include <cstdlib>
#include <ctime>
#include <fstream>
#include <iostream>
#include <mutex>
#include <random>
#include <thread>

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
std::int64_t epoch(){return static_cast<std::int64_t>(std::time(nullptr));}
const char* startup_code(const std::exception& error){
    const std::string code=error.what();
    for(const auto* known:{"STATE_DIRECTORY_OWNED","STATE_LOCK_OPEN_FAILED","DEVICE_IDENTITY_INVALID","STATE_JSON_INVALID","STATE_FILE_INVALID","STATE_FILE_UNREADABLE","STATE_FILE_TOO_LARGE","STATE_REPLACE_FAILED","PLANNER_TIME_INVALID","PASSWORD_FILE_UNREADABLE","PASSWORD_TOO_LARGE","LOG_CONFIG_INVALID","LOG_IO_FAILED","LOG_DIRECTORY_OWNED"})if(code==known)return known;
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
    if(env("NATIVE_EXPERIMENTAL")!="1") {std::cerr<<"NATIVE_EXPERIMENTAL_REQUIRED: native/server equivalence and field acceptance pending\n";return 78;}
    try {
        const auto directory=std::filesystem::path(env("STATE_DIR","/data"));
        auto config=RuntimeConfig::read([](const char* name){return env(name);});
        StateStore store(directory);Core core(config.policy);std::mutex mutex;
        ReauthPlanner planner(directory/"reauth-plan.json",config.auto_reauth_enabled,config.reauth_start_hour,config.reauth_end_hour);
        auto event=[&](auto fn){std::lock_guard guard(mutex);return fn(core);};
        auto identity=load_identity(directory,[](const char* name){return env(name);});
        const auto& client_id=identity.client_id;const auto& mac=identity.mac;
        const auto user=env("DIALER_USER"),secret=password();
        if(user.empty()||secret.empty())throw Failure("CREDENTIALS_REQUIRED");
#ifndef _WIN32
        std::signal(SIGPIPE,SIG_IGN);
#endif
        EventLog log(LogConfig::read(directory,[](const char* name){return env(name);}));
        HeartbeatLog heartbeat_log;
        std::signal(SIGINT,interrupt);std::signal(SIGTERM,interrupt);
        std::atomic_bool stop=false,report_failed=false;
        std::jthread reporter([&](std::stop_token token){
            while(!token.stop_requested()) {
                if(interrupted)stop.store(true);
                try{std::lock_guard guard(mutex);core.tick(std::chrono::steady_clock::now());auto plan=planner.snapshot(epoch());store.write(core.snapshot(),!stop.load(),epoch(),config.health_interval.count(),&plan);}
                catch(...){report_failed=true;stop=true;return;}
                for(long long i=0;i<config.health_interval.count()*10&&!token.stop_requested();++i){if(interrupted||log.failed())stop=true;std::this_thread::sleep_for(std::chrono::milliseconds(100));}
            }
        });
        CurlHttp http(stop,fixture_transport);std::unique_ptr<Session> session;
        auto urls=probe_urls(env("NETWORK_CHECK_URLS"),fixture_transport);
        if(fixture_transport)for(const auto& url:urls)if(!url.starts_with("http://127.0.0.1:"))throw Failure("FIXTURE_LOOPBACK_REQUIRED");
        NetworkMonitor monitor(config.network_interval,[&](std::atomic_bool& cancelled){CurlHttp transport(cancelled,fixture_transport);return probe(transport,urls,fixture_transport,config.probe_budget);});
        PortalPolicy portal_policy(config);
        std::optional<Probe> pending,recovery_candidate;std::uint64_t observation_sequence=0;
        AuthTrigger pending_trigger=AuthTrigger::initial;bool confirmed_once=false;
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
            if(state.phase==Phase::backoff && !pending && recovery_candidate){pending=recovery_candidate;pending_trigger=AuthTrigger::portal;session.reset();}
            try {
                auto latest=monitor.latest();
                if(latest.sequence>observation_sequence){
                    observation_sequence=latest.sequence;const auto& observation=latest.result;
                    event([&](Core& c){c.network(observation.kind==Probe::online);});
                    event([&](Core&){if(observation.kind==Probe::portal)planner.observe_portal(network_key(observation));else if(observation.kind==Probe::error)planner.invalidate_sample();});
                    if(observation.kind==Probe::portal){
                        recovery_candidate=observation;
                        const auto key=observation.portal_url+"|"+network_key(observation);
                        if(portal_policy.observe(key,state.authenticated,state.heartbeat_failures,latest.observed_at)){
                            pending=observation;pending_trigger=confirmed_once?AuthTrigger::portal:AuthTrigger::initial;
                            if(state.authenticated)portal_policy.requested(latest.observed_at);
                        }
                    }else portal_policy.non_portal();
                    }
                // Pausing authentication does not pause external reachability observations.
                if(state.phase==Phase::paused){pending.reset();session.reset();wait(Seconds(5));continue;}
                if(!pending&&session&&state.authenticated&&recovery_candidate&&event([&](Core&){return planner.should_trigger(epoch());})){
                    pending=recovery_candidate;pending_trigger=AuthTrigger::scheduled;log.write(LogEvent::scheduled_reauth);
                }
                if(pending) {
                    auto accepted=event([&](Core& c){return c.authorize(std::chrono::steady_clock::now());});
                    if(accepted) {
                        generation=*accepted;
                        heartbeat_log.reset();
                        if(session){try{session->terminate();}catch(const std::exception&){log.write(LogEvent::term_failed);}}
                        session.reset();
                        auto endpoints=discover(http,*pending,fixture_transport);
                        const auto key=endpoints.user_ip+"|"+endpoints.ac_ip;
                        active_network_key=key;
                        session=std::make_unique<Session>(http,std::move(endpoints),Identity{client_id,mac,local_time()},fixture_transport,config.heartbeat_max);
                        session->on_time(local_time);
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
                            catch(const std::exception&){if(attempt+1==config.confirmation_attempts)throw;}
                        }
                        if(confirmed){
                            event([&](Core&){planner.auth_success(pending_trigger,epoch(),std::chrono::steady_clock::now(),key);});
                            pending.reset();confirmed_once=true;portal_policy.auth_success(std::chrono::steady_clock::now());log.write(LogEvent::login_confirmed);
                        }
                    }
                } else if(session && event([&](Core& c){return c.heartbeat_due(std::chrono::steady_clock::now());})) {
                    try{auto interval=session->keep();auto failures=event([&](Core& c)->std::optional<unsigned>{auto previous_failures=c.snapshot().heartbeat_failures;if(!c.keep_confirmed(generation,interval,std::chrono::steady_clock::now()))return std::nullopt;return previous_failures;});if(failures)if(auto notice=heartbeat_log.success(*failures,interval.count(),std::chrono::steady_clock::now()))log.heartbeat(*notice);}
                    catch(const std::exception& error){auto code=event([&](Core& c){
                            auto failure=dynamic_cast<const Failure*>(&error);
                            const bool protocol_rejected=failure&&std::string(failure->what())=="HEARTBEAT_PROTOCOL_INVALID";
                            if(failure&&std::string(failure->what())=="NETWORK_CANDIDATE_CHANGED"){
                                planner.invalidate_sample();c.failed(generation,"NETWORK_CANDIDATE_CHANGED",std::chrono::steady_clock::now());
                            }else{planner.heartbeat_failure(protocol_rejected);c.heartbeat_failed(generation,std::chrono::steady_clock::now(),protocol_rejected);}
                            return c.snapshot().error;});
                        log.write(LogEvent::heartbeat_failed,code);
                        if(!event([](Core& c){return c.snapshot().authenticated;})){session.reset();}}
                }
            }catch(const Failure& error){if(stop.load())break;auto code=event([&](Core& c){planner.invalidate_sample();c.failed(generation,error.what(),std::chrono::steady_clock::now(),error.deterministic);return c.snapshot().error;});log.write(LogEvent::auth_failed,code);session.reset();}
             catch(const std::exception&){if(stop.load())break;event([&](Core& c){planner.invalidate_sample();c.failed(generation,"NATIVE_OPERATION_FAILED",std::chrono::steady_clock::now(),true);});session.reset();}
            wait(std::chrono::milliseconds(250));
        }
        monitor.stop();
        // Old work remains cancelled. A separate transport gets one bounded cleanup attempt.
        event([](Core& c){c.stop();});
        if(session){std::atomic_bool cleanup_stop=false;CurlHttp cleanup(cleanup_stop,fixture_transport);
            try{session->terminate(&cleanup);log.write(LogEvent::term_sent);}catch(const std::exception&){log.write(LogEvent::term_failed);}}
        session.reset();reporter.request_stop();reporter.join();
        auto plan=planner.snapshot(epoch());store.write(core.snapshot(),false,epoch(),config.health_interval.count(),&plan);
        log.write(LogEvent::stopped);
        if(!log.finish()){log_emergency("LOG_SINK_FAILED");return 1;}
        return report_failed?1:0;
    }catch(const Failure& e){std::string code=e.what();if(code.size()>80||code.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789")!=code.npos)code="NATIVE_STARTUP_FAILED";log_emergency(code);return 1;}
     catch(const std::exception& error){log_emergency(startup_code(error));return 1;}
}
