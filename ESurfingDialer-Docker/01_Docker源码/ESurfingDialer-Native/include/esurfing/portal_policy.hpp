#pragma once
#include "config.hpp"
#include <optional>

namespace esurfing {
class PortalPolicy {
    unsigned threshold_, hits_=0;
    Seconds grace_, cooldown_;
    std::string key_;
    std::optional<Mono> auth_at_, requested_at_;
public:
    explicit PortalPolicy(const RuntimeConfig& c):threshold_(c.portal_threshold),grace_(c.post_login_grace),cooldown_(c.portal_cooldown){}
    bool observe(const std::string& candidate_key, bool authenticated, unsigned heartbeat_failures, Mono now);
    void non_portal(){hits_=0;key_.clear();}
    void auth_success(Mono now){auth_at_=now;non_portal();}
    void requested(Mono now){requested_at_=now;}
    unsigned hits() const {return hits_;}
};
}
