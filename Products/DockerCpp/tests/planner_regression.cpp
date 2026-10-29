#include "esurfing/planner.hpp"
#include "esurfing/state_json.hpp"
#include "esurfing/state_store.hpp"
#include "esurfing/portal_policy.hpp"
#include <iostream>
#include <fstream>
#include <limits>

using namespace esurfing;
namespace {
unsigned assertions=0;
void check(bool b,const char* label){++assertions;if(!b)throw std::runtime_error(label);}
constexpr std::int64_t base=1790366400; // 2026-09-26 04:00:00 UTC+8, same as legacy fixtures.
void seed(const std::filesystem::path& path,std::int64_t schema=2,std::int64_t estimate=3600,std::int64_t auth=base,
          std::int64_t next=base+2700,std::int64_t updated=base,std::int64_t observed=0,std::int64_t samples=0){
    write_state_file(path,encode_state_json({{"schemaVersion",schema},{"estimatedLifetimeSeconds",estimate},
        {"lastAuthSuccessAt",auth},{"nextPlannedReauthAt",next},{"updatedAt",updated},
        {"lastObservedLifetimeSeconds",observed},{"successfulAuthSamples",samples}}));
}
}
int main(){
    auto dir=std::filesystem::temp_directory_path()/("esurfing-planner-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try{
        StateStore owner(dir);const Mono zero{};
        ReauthPlanner trusted(dir/"trusted.json");trusted.auth_success(AuthTrigger::initial,base,zero,"same");
        trusted.observe_portal("same");trusted.heartbeat_failure(true);trusted.auth_success(AuthTrigger::portal,base+7200,zero+Seconds(7200),"same");
        auto sample=trusted.snapshot(base+7200);
        check(sample.samples==1&&sample.observed==7200&&sample.estimate==131400,"legacy trusted correlation accepted with conservative 3:1 smoothing");
        for(const std::string scenario:{"initial","scheduled","no-heartbeat","transport","outage","changed-ip","clock-jump","restart","short"}){
            auto path=dir/(scenario+".json");ReauthPlanner p(path);p.auth_success(AuthTrigger::initial,base,zero,"same");
            ReauthPlanner restarted(path);auto& e=scenario=="restart"?restarted:p;
            e.observe_portal(scenario=="changed-ip"?"different":"same");
            if(scenario!="no-heartbeat")e.heartbeat_failure(scenario!="transport");
            if(scenario=="outage")e.invalidate_sample();
            auto trigger=scenario=="initial"?AuthTrigger::initial:scenario=="scheduled"?AuthTrigger::scheduled:AuthTrigger::portal;
            e.auth_success(trigger,base+(scenario=="short"?1800:7200),zero+Seconds(scenario=="clock-jump"?14400:scenario=="short"?1800:7200),scenario=="changed-ip"?"different":"same");
            check(e.snapshot(base+7200).samples==0,"untrusted sample excluded");
        }
        for(auto [start,end]:{std::pair{4u,6u},{22u,6u},{0u,24u},{4u,4u}}){
            ReauthPlanner e(dir/"windows.json",true,start,end);
            for(auto offset:{0,3600,20*3600,25*3600})for(auto estimate:{3600,7200,48*3600,7*86400}){
                auto next=e.compute_next(base+offset,estimate);
                check(next>=base+offset+1800&&next<=base+offset+estimate,"future bounded schedule including wrap and full day");
            }
        }
        ReauthPlanner wrapped(dir/"wrapped.json",true,22,6);
        check(wrapped.in_window(base-5*3600)&&wrapped.in_window(base)&&!wrapped.in_window(base+2*3600),"cross-midnight boundaries");
        ReauthPlanner exact(dir/"exact.json");
        check(exact.compute_next(base,48*3600)==base+24*3600,"known legacy UTC+8 aligned target");
        check(exact.compute_next(base,3600)==base+2700,"short learned interval keeps 45 minute target");
        check(exact.in_window(base)&&!exact.in_window(base-1)&&!exact.in_window(base+7200),"half-open window second boundaries");
        const auto far_auth=ReauthPlanner::max_epoch-ReauthPlanner::max_interval-1;
        const auto far_next=exact.compute_next(far_auth,ReauthPlanner::max_interval);
        check(far_next>=far_auth+ReauthPlanner::min_gap&&far_next<=far_auth+ReauthPlanner::max_interval,"far-future schedule stays inside 64-bit epoch bounds");
        exact.auth_success(AuthTrigger::initial,base,zero,"same");
        check(exact.should_trigger(base+86400),"due plan triggers in safe window");
        seed(dir/"outside.json",2,3600,base,base+3*3600);
        ReauthPlanner outside(dir/"outside.json");
        check(outside.waiting(base+3*3600)&&!outside.should_trigger(base+3*3600),"outside window waits");
        check(outside.should_trigger(base+5*3600)&&!outside.waiting(base+5*3600),"overdue window grace permits fallback");
        ReauthPlanner disabled(dir/"disabled.json",false);disabled.auth_success(AuthTrigger::initial,base,zero,"same");
        check(!disabled.should_trigger(base+3*86400)&&!disabled.waiting(base+3*86400),"disabled planner never triggers");
        ReauthPlanner rollback(dir/"rollback.json");rollback.auth_success(AuthTrigger::initial,base,zero,"same");
        check(!rollback.should_trigger(base-300)&&rollback.snapshot(base-300).last_auth==0,"backward clock discards schedule");
        seed(dir/"legacy.json",1,3600,base,base-1800,base,3600,3);
        ReauthPlanner legacy(dir/"legacy.json");auto migrated=legacy.snapshot(base);
        check(migrated.next==base+2700&&migrated.samples==0&&migrated.observed==0,"schema1 target repaired and old unqualified counters discarded");
        check(std::get<std::int64_t>(parse_state_json(read_state_file(dir/"legacy.json")).at("schemaVersion"))==2,"schema1 atomically upgraded");
        for(const std::string kind:{"future-auth","future-update","huge-next","invalid-estimate","negative-samples","malformed","duplicate","oversized","boolean-number","schema3"}){
            auto path=dir/("invalid-"+kind+".json");
            seed(path,kind=="schema3"?3:2,kind=="invalid-estimate"?1:3600,kind=="future-auth"?base+300:base,
                kind=="huge-next"?std::numeric_limits<std::int64_t>::max():base+2700,kind=="future-update"?base+300:base,0,kind=="negative-samples"?-1:0);
            if(kind=="malformed")write_state_file(path,"{partial");
            if(kind=="duplicate")write_state_file(path,"{\"schemaVersion\":2,\"schemaVersion\":2}");
            if(kind=="boolean-number"){auto o=parse_state_json(read_state_file(path));o["successfulAuthSamples"]=false;write_state_file(path,encode_state_json(o));}
            if(kind=="oversized"){std::ofstream out(path);out<<std::string(32769,'x');}
            auto original=read_state_file(path,40000);ReauthPlanner e(path);auto s=e.snapshot(base);
            check(s.last_auth==0&&s.estimate==ReauthPlanner::default_interval&&s.next==0,"invalid stored plan falls back safely");
            check(read_state_file(path,40000)==original,"invalid stored evidence retained until next auth");
        }
        auto failpath=dir/"write-failure.json";seed(failpath);auto old=read_state_file(failpath);
        ReauthPlanner failed(failpath,true,4,6,[](const auto&,const auto&){throw std::runtime_error("synthetic failure");});
        failed.auth_success(AuthTrigger::initial,base,zero,"same");
        check(read_state_file(failpath)==old&&failed.snapshot(base).next>base&&!failed.snapshot(base).persistence_ok,"write failure preserves old plan and in-memory recovery");
        owner.write(Snapshot{},true,base,10,&sample);
        auto health=parse_state_json(read_state_file(dir/"health.json"));
        check(std::get<std::int64_t>(health.at("autoReauthSuccessfulAuthSamples"))==1&&std::get<bool>(health.at("autoReauthPersistenceOk")),"planner health telemetry uses separate network/session schema");
        bool invalid=false;try{ReauthPlanner bad(dir/"bad.json",true,24,6);}catch(const std::exception&){invalid=true;}check(invalid,"invalid safe window rejected");
        invalid=false;try{exact.snapshot(std::numeric_limits<std::int64_t>::max());}catch(const std::exception&){invalid=true;}check(invalid,"epoch overflow rejected");
        RuntimeConfig cfg;cfg.portal_threshold=3;PortalPolicy portal(cfg);
        check(portal.observe("A",false,0,zero),"no-session portal requests initial auth without uptime cooldown");
        portal.auth_success(zero);
        for(int i=1;i<=3;++i)check(!portal.observe("A",true,0,zero+Seconds(i)),"post-login grace suppresses stale portal pages");
        check(portal.observe("A",true,0,zero+Seconds(21)),"grace uses auth time independently of successful keeps");
        portal.requested(zero+Seconds(21));
        check(!portal.observe("A",true,1,zero+Seconds(30)),"heartbeat corroboration respects portal cooldown");
        check(portal.observe("A",true,1,zero+Seconds(321)),"portal cooldown expires monotonically");
        portal.auth_success(zero+Seconds(400));
        check(!portal.observe("A",true,0,zero+Seconds(421)),"first fresh portal below threshold");
        check(!portal.observe("B",true,0,zero+Seconds(422))&&portal.hits()==1,"different candidates cannot accumulate one threshold");
        check(!portal.observe("A",true,0,zero+Seconds(423))&&portal.hits()==1,"alternating networks restart candidate count");
        check(portal.observe("A",true,1,zero+Seconds(424)),"heartbeat failure corroborates below threshold");
        portal.non_portal();check(portal.hits()==0,"online/error breaks consecutive portal evidence");
        std::cout<<assertions<<" planner assertions passed; synthetic clocks and legacy state fixtures only\n";
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';std::filesystem::remove_all(dir);return 1;}
    std::filesystem::remove_all(dir);return 0;
}
