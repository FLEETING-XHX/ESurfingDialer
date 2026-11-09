#include "esurfing/planner.hpp"
#include "esurfing/state_json.hpp"
#include <algorithm>

namespace esurfing {
namespace {
constexpr std::int64_t day_seconds=86400, utc8=8*3600;
void valid_wall(std::int64_t wall){
    if(wall<1||wall>RestartPlanner::max_epoch-2*day_seconds)throw std::invalid_argument("PLANNER_TIME_INVALID");
}
std::int64_t day(std::int64_t wall){return (wall+utc8)/day_seconds;}
std::int64_t number(const JsonObject& object,const char* name){
    auto it=object.find(name);
    if(it!=object.end())if(auto value=std::get_if<std::int64_t>(&it->second))return *value;
    throw std::runtime_error("RESTART_STATE_INVALID");
}
}
RestartPlanner::RestartPlanner(std::filesystem::path file,bool enabled,unsigned start,unsigned end,Mono started,Writer writer)
    :file_(std::move(file)),started_(started),writer_(std::move(writer)){
    if(start>23||end>24||start>=end)throw std::invalid_argument("RESTART_WINDOW_INVALID");
    state_.enabled=enabled;state_.start_hour=start;state_.end_hour=end;
    if(!writer_)writer_=[](const auto& path,const auto& data){write_state_file(path,data);};
}
void RestartPlanner::load(std::int64_t wall){
    valid_wall(wall);
    if(loaded_)return;
    loaded_=true;
    try{
        if(!std::filesystem::exists(std::filesystem::symlink_status(file_)))return;
        auto object=parse_state_json(read_state_file(file_));
        auto last=number(object,"lastRestartAt");
        if(number(object,"schemaVersion")!=1||last<1||last>max_epoch-2*day_seconds)throw std::runtime_error("RESTART_STATE_INVALID");
        // Keep future dates after clock rollback; never clear the daily deduplication.
        state_.last_restart=last;
    }catch(const std::exception&){state_.persistence_ok=false;}
}
RestartSnapshot RestartPlanner::snapshot(std::int64_t wall){
    load(wall);
    state_.next=0;
    if(state_.enabled&&state_.persistence_ok){
        auto target_day=day(wall);
        if(state_.last_restart)target_day=std::max(target_day,day(state_.last_restart)+1);
        auto midnight=target_day*day_seconds-utc8;
        if(wall>=midnight+state_.end_hour*3600)midnight+=day_seconds;
        state_.next=midnight+state_.start_hour*3600;
    }
    return state_;
}
bool RestartPlanner::claim(std::int64_t wall,Mono now){
    auto plan=snapshot(wall);
    auto seconds=(wall+utc8)%day_seconds;
    if(!plan.enabled||!plan.persistence_ok||!plan.next||wall<plan.next||
       seconds<plan.start_hour*3600||seconds>=plan.end_hour*3600||now-started_<minimum_uptime)return false;
    try{
        writer_(file_,encode_state_json({{"schemaVersion",std::int64_t{1}},{"lastRestartAt",wall}}));
        state_.last_restart=wall;
        return true;
    }catch(const std::exception&){state_.persistence_ok=false;return false;}
}
}
