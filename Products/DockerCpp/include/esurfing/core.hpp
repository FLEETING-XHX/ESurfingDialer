#pragma once
#include <chrono>
#include <cstdint>
#include <optional>
#include <stdexcept>
#include <string>

namespace esurfing {
using Mono = std::chrono::steady_clock::time_point;
using Seconds = std::chrono::seconds;
enum class Phase { waiting_network, external_online, authorizing, confirming, online, backoff, paused, stopped };
const char* phase_name(Phase);
struct Policy {
    unsigned max_recoveries = 3;
    unsigned deterministic_limit = 3;
    unsigned heartbeat_failure_limit = 3;
    Seconds retry_initial{5}, retry_max{60}, healthy_reset{120}, operation_timeout{90};
    void validate() const;
};
struct Snapshot {
    Phase phase = Phase::waiting_network;
    bool network_online = false, authenticated = false;
    std::uint64_t generation = 0;
    unsigned recovery_attempts = 0, heartbeat_failures = 0;
    std::string error;
};
// No threads, networking or wall-clock reads. The owning loop serializes events.
class Core {
    Policy policy_;
    Snapshot state_;
    Mono progress_{}, next_retry_{}, healthy_since_{};
    std::optional<Mono> heartbeat_due_;
    unsigned deterministic_failures_ = 0;
    std::string last_deterministic_;
    Seconds heartbeat_interval_{240};
    bool current(std::uint64_t generation) const;
    void recover(std::string code, Mono now, bool deterministic);
public:
    explicit Core(Policy policy = {});
    const Snapshot& snapshot() const { return state_; }
    void network(bool online);
    std::optional<std::uint64_t> authorize(Mono now);
    bool login_accepted(std::uint64_t generation, Mono now);
    void progress(std::uint64_t generation, Mono now);
    bool keep_confirmed(std::uint64_t generation, Seconds interval, Mono now);
    void failed(std::uint64_t generation, std::string code, Mono now, bool deterministic = false);
    bool heartbeat_due(Mono now) const;
    void heartbeat_failed(std::uint64_t generation, Mono now, bool protocol_rejected=false);
    void tick(Mono now);
    void stop();
    void resume();
};
std::string json_quote(const std::string&);
}
