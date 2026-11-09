#pragma once
#include <filesystem>
#include "core.hpp"
#include <map>
#include <string>
#include <vector>
namespace esurfing {
struct UplinkSnapshot {
    enum Link { unknown, up, down, missing } link=unknown;
    std::string interface_name, logical_interface;
    Link logical_link=unknown;
    bool configured=false, physical=false, ipv4_default=false, route_readable=false;
    Mono observed{};
    std::string signature() const;
    std::string describe() const;
};
std::string uplink_diagnosis(const UplinkSnapshot&, Mono now);
// Reads only interface names and link state; never addresses, SSIDs or packet data.
class LinkMonitor {
    std::filesystem::path root_;
    std::map<std::string,std::string> previous_;
    bool unavailable_=false;
    std::string wan_,physical_,candidate_;
    std::filesystem::path route_;
    UplinkSnapshot uplink_;
public:
    explicit LinkMonitor(std::filesystem::path root="/sys/class/net",std::string wan={},std::string physical={},
                         std::filesystem::path route="/proc/net/route");
    const UplinkSnapshot& uplink() const {return uplink_;}
    std::vector<std::string> poll();
};
}
