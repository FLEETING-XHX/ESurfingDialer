#include "esurfing/network_monitor.hpp"

namespace esurfing {
NetworkMonitor::NetworkMonitor(Seconds interval,Producer produce){
    if(interval<Seconds(1)||interval>Seconds(300)||!produce)throw Failure("NETWORK_MONITOR_POLICY_INVALID");
    thread_=std::jthread([this,interval,produce=std::move(produce)](std::stop_token token){
        std::stop_callback cancel(token,[this]{cancelled_=true;});
        try{
            while(!token.stop_requested()){
                Probe result;
                try{result=produce(cancelled_);}catch(const std::exception&){if(token.stop_requested())break;}
                if(token.stop_requested())break;
                {std::lock_guard guard(mutex_);observation_.result=std::move(result);observation_.observed_at=std::chrono::steady_clock::now();++observation_.sequence;}
                std::unique_lock lock(wait_mutex_);wait_.wait_for(lock,token,interval,[]{return false;});
            }
        }catch(...){failed_=true;}
    });
}
NetworkMonitor::~NetworkMonitor(){stop();}
void NetworkMonitor::stop(){thread_.request_stop();if(thread_.joinable())thread_.join();}
NetworkObservation NetworkMonitor::latest(){std::lock_guard guard(mutex_);return observation_;}
}
