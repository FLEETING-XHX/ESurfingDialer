#include "esurfing/planner.hpp"
#include "esurfing/state_json.hpp"
#include <algorithm>
#include <cstdlib>

namespace esurfing {
namespace {
void valid_wall(std::int64_t wall){if(wall<1||wall>ReauthPlanner::max_epoch-ReauthPlanner::max_interval)throw std::invalid_argument("PLANNER_TIME_INVALID");}
std::int64_t number(const JsonObject& o,const char* name){auto it=o.find(name);if(it!=o.end())if(auto n=std::get_if<std::int64_t>(&it->second))return *n;throw std::runtime_error("PLANNER_STATE_INVALID");}
}
ReauthPlanner::ReauthPlanner(std::filesystem::path file,bool enabled,unsigned start,unsigned end,Writer writer):file_(std::move(file)),writer_(std::move(writer)){
    if(start>23||end>24)throw std::invalid_argument("PLANNER_WINDOW_INVALID");
    state_.enabled=enabled;state_.start_hour=start;state_.end_hour=end;
    if(!writer_)writer_=[](const auto& path,const auto& data){write_state_file(path,data);};
}
void ReauthPlanner::reset(){state_.estimate=default_interval;state_.last_auth=state_.next=state_.observed=0;state_.samples=0;}
void ReauthPlanner::persist(std::int64_t wall){
    try{
        writer_(file_,encode_state_json({{"schemaVersion",std::int64_t{2}},
            {"estimatedLifetimeSeconds",state_.estimate},{"lastAuthSuccessAt",state_.last_auth},
            {"nextPlannedReauthAt",state_.next},{"lastObservedLifetimeSeconds",state_.observed},
            {"successfulAuthSamples",static_cast<std::int64_t>(state_.samples)},{"updatedAt",wall}}));
        state_.persistence_ok=true;
    }catch(const std::exception&){state_.persistence_ok=false;}
}
void ReauthPlanner::load(std::int64_t wall){
    try{
        if(!std::filesystem::exists(std::filesystem::symlink_status(file_)))return;
        auto object=parse_state_json(read_state_file(file_));
        auto schema=object.contains("schemaVersion")?number(object,"schemaVersion"):1;
        auto estimate=number(object,"estimatedLifetimeSeconds"),auth=number(object,"lastAuthSuccessAt"),
            next=number(object,"nextPlannedReauthAt"),updated=number(object,"updatedAt"),
            observed=number(object,"lastObservedLifetimeSeconds"),samples=number(object,"successfulAuthSamples");
        if(schema<1||schema>2||estimate<min_interval||estimate>max_interval||auth<0||auth>wall||updated<1||updated>wall||
           auth>updated||observed<0||observed>max_interval||samples<0||samples>1000000||next<0||next>wall+max_interval||(!auth&&next))throw std::runtime_error("PLANNER_STATE_INVALID");
        auto repaired=auth>0&&next<auth+min_gap?compute_next(auth,estimate):next;
        state_.estimate=estimate;state_.last_auth=auth;state_.next=repaired;
        state_.observed=schema==2?observed:0;state_.samples=schema==2?static_cast<unsigned>(samples):0;
        if(schema==1||repaired!=next)persist(wall);
    }catch(const std::exception&){reset();}
    eligible_=false; // A process restart cannot constitute a lifetime sample.
}
void ReauthPlanner::ensure_loaded(std::int64_t wall){
    valid_wall(wall);
    if(!loaded_){load(wall);loaded_=true;}
    if(wall<state_.last_auth||(last_wall_>0&&wall<last_wall_-120)){
        auto estimate=state_.estimate;reset();state_.estimate=estimate;eligible_=false;key_.clear();persist(wall);
    }
    last_wall_=wall;
}
PlanSnapshot ReauthPlanner::snapshot(std::int64_t wall){ensure_loaded(wall);return state_;}
void ReauthPlanner::observe_portal(const std::string& key){if(key.empty()||key!=key_)eligible_=false;else portal_observed_=true;}
void ReauthPlanner::heartbeat_failure(bool rejected){if(rejected)heartbeat_rejected_=true;else eligible_=false;}
void ReauthPlanner::auth_success(AuthTrigger trigger,std::int64_t wall,Mono mono,const std::string& key){
    ensure_loaded(wall);
    auto observed=wall-state_.last_auth,elapsed=std::chrono::duration_cast<Seconds>(mono-baseline_).count();
    bool trusted=trigger==AuthTrigger::portal&&eligible_&&portal_observed_&&heartbeat_rejected_&&!key_.empty()&&key==key_&&
        observed>=min_interval&&observed<=max_interval&&elapsed>=0&&std::abs(elapsed-observed)<=120;
    if(trusted){state_.estimate=std::clamp((state_.estimate*3+observed)/4,min_interval,max_interval);state_.observed=observed;state_.samples=std::min(state_.samples+1,1000000u);}
    state_.last_auth=wall;state_.next=compute_next(wall,state_.estimate);key_=key;baseline_=mono;
    eligible_=!key.empty();portal_observed_=heartbeat_rejected_=false;persist(wall);
}
bool ReauthPlanner::in_window(std::int64_t wall) const {
    valid_wall(wall);
    auto minutes=((wall+8*3600)%86400)/60;
    auto start=state_.start_hour*60,end=state_.end_hour*60;
    if(end>start)return minutes>=start&&minutes<end;
    if(end<start)return minutes>=start||minutes<end;
    return true;
}
std::int64_t ReauthPlanner::compute_next(std::int64_t auth,std::int64_t estimate) const {
    valid_wall(auth);
    auto bounded=std::clamp(estimate,min_interval,max_interval);
    auto target=auth+std::max(min_gap,bounded-std::min(std::int64_t{90*60},bounded/4));
    // This helper also checks targets near MAX_EPOCH without overflowing or
    // depending on the host's time_t width / configured logging timezone.
    auto minutes=((target+8*3600)%86400)/60;
    auto start=state_.start_hour*60,end=state_.end_hour*60;
    if(end==start||(end>start?minutes>=start&&minutes<end:minutes>=start||minutes<end))return target;
    auto aligned=target-((target+8*3600)%86400)+state_.start_hour*3600;
    if(aligned>target)aligned-=86400;
    return aligned>=auth+min_gap?aligned:target;
}
bool ReauthPlanner::overdue(std::int64_t wall) const {
    auto start=state_.start_hour,end=state_.end_hour;
    auto hours=end==start?24:end>start?end-start:24-start+end;
    return wall-state_.next>=static_cast<std::int64_t>(hours)*3600;
}
bool ReauthPlanner::should_trigger(std::int64_t wall){ensure_loaded(wall);return state_.enabled&&state_.next>0&&wall>=state_.next&&(in_window(wall)||overdue(wall));}
bool ReauthPlanner::waiting(std::int64_t wall){ensure_loaded(wall);return state_.enabled&&state_.next>0&&wall>=state_.next&&!in_window(wall)&&!overdue(wall);}
}
