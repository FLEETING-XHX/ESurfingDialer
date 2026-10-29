#pragma once
#include "http.hpp"
#include <condition_variable>
#include <functional>
#include <mutex>
#include <thread>

namespace esurfing {
struct NetworkObservation { std::uint64_t sequence=0; Probe result; Mono observed_at{}; };
// Owns only probe work. It never mutates an authentication session or Core.
class NetworkMonitor {
    std::mutex mutex_, wait_mutex_;
    std::condition_variable_any wait_;
    NetworkObservation observation_;
    std::atomic_bool cancelled_=false, failed_=false;
    std::jthread thread_;
public:
    using Producer=std::function<Probe(std::atomic_bool&)>;
    NetworkMonitor(Seconds interval, Producer);
    ~NetworkMonitor();
    NetworkMonitor(const NetworkMonitor&)=delete;
    NetworkMonitor& operator=(const NetworkMonitor&)=delete;
    NetworkObservation latest();
    bool failed() const {return failed_.load();}
    void stop();
};
}
