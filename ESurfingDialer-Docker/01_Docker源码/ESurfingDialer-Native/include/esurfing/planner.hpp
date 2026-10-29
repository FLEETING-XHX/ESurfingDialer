#pragma once
#include "core.hpp"
#include <filesystem>
#include <functional>

namespace esurfing {
enum class AuthTrigger { initial, portal, scheduled };
struct PlanSnapshot {
    bool enabled=true;
    std::int64_t estimate=48*3600, last_auth=0, next=0, observed=0;
    unsigned samples=0, start_hour=4, end_hour=6;
    bool persistence_ok=true;
};
// Caller serializes operations. Wall time schedules the UTC+8 window; monotonic
// time is used solely to qualify samples, never to infer an operator policy.
class ReauthPlanner {
public:
    using Writer=std::function<void(const std::filesystem::path&,const std::string&)>;
    static constexpr std::int64_t default_interval=48*3600, min_interval=3600,
        max_interval=7*86400, min_gap=1800, max_epoch=253402300799;
    ReauthPlanner(std::filesystem::path, bool enabled=true, unsigned start=4, unsigned end=6, Writer writer={});
    void invalidate_sample() { eligible_=false; }
    void observe_portal(const std::string& network_key);
    void heartbeat_failure(bool protocol_rejected);
    void auth_success(AuthTrigger, std::int64_t wall, Mono mono, const std::string& network_key);
    PlanSnapshot snapshot(std::int64_t wall);
    bool should_trigger(std::int64_t wall);
    bool waiting(std::int64_t wall);
    std::int64_t compute_next(std::int64_t auth, std::int64_t estimate) const;
    bool in_window(std::int64_t wall) const;
private:
    std::filesystem::path file_;
    Writer writer_;
    PlanSnapshot state_;
    bool loaded_=false, eligible_=false, portal_observed_=false, heartbeat_rejected_=false;
    std::int64_t last_wall_=0;
    Mono baseline_{};
    std::string key_;
    void ensure_loaded(std::int64_t wall);
    void load(std::int64_t wall);
    void persist(std::int64_t wall);
    void reset();
    bool overdue(std::int64_t wall) const;
};
}
