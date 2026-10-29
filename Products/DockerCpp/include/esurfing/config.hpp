#pragma once
#include "core.hpp"
#include <functional>
#include <filesystem>

namespace esurfing {
std::string read_password_file(const std::filesystem::path&);
struct RuntimeConfig {
    Policy policy;
    Seconds probe_budget{60}, network_interval{5}, health_interval{10}, heartbeat_max{240};
    Seconds post_login_grace{20}, portal_cooldown{300}, confirmation_interval{2};
    unsigned portal_threshold=12, confirmation_attempts=3;
    bool auto_reauth_enabled=true;
    unsigned reauth_start_hour=4, reauth_end_hour=6;
    static RuntimeConfig read(const std::function<std::string(const char*)>& env);
};
}
