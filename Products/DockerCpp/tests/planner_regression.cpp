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
void check(bool value,const char* label){++assertions;if(!value)throw std::runtime_error(label);}
constexpr std::int64_t base=1790366400; // 2026-09-26 04:00:00 UTC+8.
}
int main(){
    auto dir=std::filesystem::temp_directory_path()/("esurfing-restart-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try{
        StateStore owner(dir);const Mono zero{};const auto running=zero+Seconds(30);
        RestartPlanner exact(dir/"daily.json",true,4,6,zero);
        check(exact.snapshot(base-1).next==base,"next allowed window begins at 04:00 UTC+8");
        check(!exact.claim(base-1,running),"never before 04:00");
        check(!exact.claim(base,zero+Seconds(14)),"startup grace protects Docker restart monitoring");
        check(exact.claim(base,zero+Seconds(15)),"first eligible check at window start can restart");
        auto stored=read_state_file(dir/"daily.json");
        check(!exact.claim(base+60,running)&&!exact.claim(base+7199,running),"once per local calendar day");
        check(read_state_file(dir/"daily.json")==stored,"duplicate check does not rewrite claim");
        RestartPlanner restarted(dir/"daily.json",true,4,6,zero);
        check(!restarted.claim(base+60,running),"new process in same window cannot restart twice");
        check(restarted.snapshot(base+60).next==base+86400,"next day independent of authentication lifetime");
        check(!restarted.claim(base-86400,running),"clock rollback preserves calendar-day high water mark");
        check(restarted.claim(base+86400+300,running),"next day can restart anywhere inside allowed window");
        for(auto offset:{0,60,3600,7199}){
            RestartPlanner inside(dir/("inside-"+std::to_string(offset)+".json"),true,4,6,zero);
            check(inside.claim(base+offset,running),"no fixed 04:01 requirement");
        }
        RestartPlanner missed(dir/"missed.json",true,4,6,zero);
        check(!missed.claim(base+7200,running)&&!missed.claim(base+12*3600,running),"06:00 inclusive and later never catch up");
        check(missed.snapshot(base+7200).next==base+86400,"missed window advances to next day");
        check(!missed.claim(base+86400-1,running)&&missed.claim(base+86400,running),"next window boundary");
        RestartPlanner custom(dir/"custom.json",true,5,7,zero);
        check(!custom.claim(base+3599,running)&&custom.claim(base+3600,running),"editable window retains daily frequency");
        RestartPlanner disabled(dir/"disabled.json",false,4,6,zero);
        check(!disabled.claim(base,running)&&disabled.snapshot(base).next==0&&!std::filesystem::exists(dir/"disabled.json"),"disabled never schedules or writes state");
        auto failpath=dir/"write-failure.json";unsigned writes=0;
        RestartPlanner failed(failpath,true,4,6,zero,[&](const auto&,const auto&){++writes;throw std::runtime_error("synthetic failure");});
        check(!failed.claim(base,running)&&!failed.snapshot(base).persistence_ok,"failed persistence prevents restart loop");
        check(!failed.claim(base+1,running)&&writes==1,"failed claim does not busy-loop disk writes");
        for(const std::string text:{"{partial",R"({"schemaVersion":2,"lastRestartAt":1790366400})",R"({"schemaVersion":1,"lastRestartAt":-1})",R"({"schemaVersion":1,"lastRestartAt":true})"}){
            auto path=dir/"invalid.json";write_state_file(path,text);RestartPlanner invalid(path,true,4,6,zero);
            check(!invalid.claim(base,running)&&!invalid.snapshot(base).persistence_ok&&read_state_file(path)==text,"corrupt restart evidence preserved; authentication can keep running");
        }
        // Old lifetime-learning evidence is deliberately neither loaded nor rewritten.
        write_state_file(dir/"reauth-plan.json",R"({"schemaVersion":2,"estimatedLifetimeSeconds":172800})");
        RestartPlanner replacement(dir/"new-daily.json",true,4,6,zero);
        check(replacement.snapshot(base).next==base&&read_state_file(dir/"reauth-plan.json").find("172800")!=std::string::npos,"old reauthentication plan retained but inactive");
        auto plan=restarted.snapshot(base+86400+300);owner.write(Snapshot{},true,base+86400+300,10,&plan);
        auto health=parse_state_json(read_state_file(dir/"health.json"));
        check(std::get<std::int64_t>(health.at("dailyRestartLastTriggeredAt"))==base+86400+300&&std::get<bool>(health.at("dailyRestartPersistenceOk"))&&!health.contains("autoReauthEnabled"),"health reports daily restart rather than lifetime learning");
        bool invalid=false;try{RestartPlanner bad(dir/"bad.json",true,6,4);}catch(const std::exception&){invalid=true;}check(invalid,"empty or overnight window rejected");
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
        std::cout<<assertions<<" daily restart and portal assertions passed; synthetic clocks only\n";
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';std::filesystem::remove_all(dir);return 1;}
    std::filesystem::remove_all(dir);return 0;
}
