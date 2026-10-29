#pragma once
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>
#include "core.hpp"

namespace esurfing {
inline constexpr const char* docker_user_agent = "CCTP/Android8_vpn/2082";
struct Failure:std::runtime_error {
    bool deterministic;
    explicit Failure(const char* code, bool deterministic = true): std::runtime_error(code), deterministic(deterministic) {}
};
struct Endpoints { std::string auth, ticket, user_ip, ac_ip, source; };
struct Login { std::string keep, term; Seconds interval; };
std::string safe_url(std::string_view, bool allow_loopback = false);
std::string resolve_url(std::string_view base, std::string_view relative, bool allow_loopback = false);
std::map<std::string,std::vector<std::string>> query_parameters(std::string_view);
bool ipv4(std::string_view);
std::string xml_escape(std::string_view);
std::string request_xml(const std::vector<std::pair<std::string,std::string>>& fields);
std::string response_ticket(std::string_view);
Login response_login(std::string_view, bool allow_loopback = false, Seconds maximum=Seconds(240));
Seconds response_keep(std::string_view, Seconds maximum=Seconds(240));
std::optional<Endpoints> portal_config(std::string_view, bool allow_loopback = false);
std::string checksum(std::string_view);
}
