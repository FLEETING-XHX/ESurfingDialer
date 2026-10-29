#include "esurfing/network_monitor.hpp"
#include <future>
#include <iostream>

using namespace esurfing;
int main(){try{
    std::promise<void> entered;auto ready=entered.get_future();std::atomic_bool saw_cancel=false;
    NetworkMonitor monitor(Seconds(300),[&](std::atomic_bool& cancel){
        entered.set_value();while(!cancel.load())std::this_thread::sleep_for(std::chrono::milliseconds(1));saw_cancel=true;return Probe{};
    });
    if(ready.wait_for(Seconds(2))!=std::future_status::ready)throw Failure("PROBE_DID_NOT_START");
    if(monitor.latest().sequence!=0)throw Failure("INCOMPLETE_PROBE_PUBLISHED");
    // Real probe worker remains blocked while the authentication owner advances.
    Core core;const Mono zero{};auto g=*core.authorize(zero);core.login_accepted(g,zero);core.keep_confirmed(g,Seconds(5),zero);
    for(int i=1;i<=50;++i){auto now=zero+Seconds(i*5);if(!core.heartbeat_due(now))throw Failure("HEARTBEAT_NOT_DUE");core.keep_confirmed(g,Seconds(5),now);}
    if(!core.snapshot().authenticated||monitor.latest().sequence!=0)throw Failure("SLOW_PROBE_BLOCKED_HEARTBEAT");
    auto started=std::chrono::steady_clock::now();monitor.stop();
    if(!saw_cancel||std::chrono::steady_clock::now()-started>Seconds(2)||monitor.failed())throw Failure("PROBE_STOP_NOT_BOUNDED");
    NetworkMonitor immediate(Seconds(300),[](std::atomic_bool&){return Probe{Probe::online,{},{}};});
    auto deadline=std::chrono::steady_clock::now()+Seconds(2);NetworkObservation observation;
    while((observation=immediate.latest()).sequence==0&&std::chrono::steady_clock::now()<deadline)std::this_thread::yield();
    if(observation.sequence!=1||observation.result.kind!=Probe::online||observation.observed_at==Mono{})throw Failure("OBSERVATION_NOT_ATOMIC");
    immediate.stop();
    std::cout<<"slow probe concurrency, 50 independent heartbeat deadlines, cancellation and atomic observation passed; no network\n";
    return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
