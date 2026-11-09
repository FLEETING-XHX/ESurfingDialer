#pragma once
#include "core.hpp"
#include <filesystem>
#include <functional>

namespace esurfing {
struct RestartSnapshot {
    bool enabled=true;
    std::int64_t next=0, last_restart=0;
    unsigned start_hour=4, end_hour=6;
    bool persistence_ok=true;
    bool operator==(const RestartSnapshot&) const = default;
};
// One owner serializes access. Calendar days and the allowed window use UTC+8.
// Persist the claim before exiting, so the next process cannot restart again today.
class RestartPlanner {
public:
    using Writer=std::function<void(const std::filesystem::path&,const std::string&)>;
    static constexpr std::int64_t max_epoch=253402300799;
    // Docker must observe a successful startup before relying on its restart policy.
    static constexpr Seconds minimum_uptime{15};
    RestartPlanner(std::filesystem::path, bool enabled=true, unsigned start=4, unsigned end=6,
                   Mono started=std::chrono::steady_clock::now(), Writer writer={});
    RestartSnapshot snapshot(std::int64_t wall);
    bool claim(std::int64_t wall, Mono now);
private:
    std::filesystem::path file_;
    RestartSnapshot state_;
    Mono started_;
    Writer writer_;
    bool loaded_=false;
    void load(std::int64_t wall);
};
}
