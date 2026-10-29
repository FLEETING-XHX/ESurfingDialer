#pragma once
#include "core.hpp"
#include "planner.hpp"
#include <filesystem>

namespace esurfing {
class StateStore {
    std::filesystem::path directory_;
    std::intptr_t handle_ = -1;
    std::string instance_;
public:
    explicit StateStore(std::filesystem::path directory);
    ~StateStore();
    StateStore(const StateStore&) = delete;
    StateStore& operator=(const StateStore&) = delete;
    const std::string& instance() const { return instance_; }
    void write(const Snapshot&, bool alive, std::int64_t epoch_seconds, long long report_interval=10, const PlanSnapshot* plan=nullptr);
};
}
