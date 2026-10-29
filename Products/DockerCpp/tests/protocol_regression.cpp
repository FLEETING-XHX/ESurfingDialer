#include "esurfing/session.hpp"
#include <iostream>
#include <deque>
using namespace esurfing;
unsigned checks=0;
void check(bool b,const char* msg){++checks;if(!b)throw std::runtime_error(msg);}
template<class F>void rejects(F f){bool rejected=false;try{f();}catch(const std::exception&){rejected=true;}check(rejected,"MUST_REJECT");}
struct Server:Http {
    std::string algorithm,expected_client="synthetic-client";
    Cipher cipher;
    explicit Server(std::string id="07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA"):algorithm(std::move(id)),cipher(algorithm){}
    unsigned calls=0;std::string last_url;bool invalid_keep=false;
    HttpResponse perform(const HttpRequest& r) override {
        check(r.body.has_value(),"POST_EXPECTED");check(r.headers.at("CDC-Checksum")==checksum(*r.body),"CHECKSUM_MUST_COVER_CIPHERTEXT");
        last_url=r.url;++calls;
        check(r.headers.at("Client-ID")==expected_client,"FROZEN_SESSION_CLIENT_ID");
        check(r.headers.at("Algo-ID")== (calls==1?"00000000-0000-0000-0000-000000000000":algorithm),"ALGORITHM_HEADER_BELONGS_TO_THIS_SESSION");
        if(calls==1){check(*r.body=="00000000-0000-0000-0000-000000000000","BOOTSTRAP_NOT_ENCRYPTED");
            std::string module(4,'\0'); module+=char(36); module+=algorithm;return {200,module,{}};}
        auto xml=cipher.decrypt(*r.body);
        check(xml.find("synthetic-time-"+std::to_string(calls-1))!=xml.npos,"REFRESH_LOCAL_TIME_PER_REQUEST");
        check(xml.find(docker_user_agent)!=xml.npos,"DOCKER_CHANNEL_PRESERVED");
        check(xml.find("10.0.0.2")!=xml.npos||calls==3,"FROZEN_IP");
        if(calls==2)return {200,cipher.encrypt("<response><ticket>synthetic-ticket</ticket></response>"),{}};
        if(calls==3){check(xml.find("u&lt;&amp;")!=xml.npos,"CREDENTIAL_XML_ESCAPED");return {200,cipher.encrypt("<response><keep-url>http://10.0.0.9/keep.cgi</keep-url><term-url>http://10.0.0.8/term.cgi</term-url><keep-retry>240</keep-retry></response>"),{}};}
        if(calls==4)return {200,cipher.encrypt(invalid_keep?"<response><error>synthetic</error></response>":"<response><interval>240</interval></response>"),{}};
        return {200,"",{}};
    }
};
struct Script:Http { std::deque<HttpResponse> responses;std::vector<long> timeouts;HttpResponse perform(const HttpRequest& request) override{timeouts.push_back(request.timeout_seconds);if(responses.empty())throw Failure("TEST_SCRIPT_EMPTY");auto r=responses.front();responses.pop_front();return r;} };
int main(){try{
    check(checksum("")=="d41d8cd98f00b204e9800998ecf8427e","MD5_EMPTY_VECTOR");
    check(checksum("abc")=="900150983cd24fb0d6963f7d28e17f72","MD5_ABC_VECTOR");
    check(probe_urls("  , ,\n").size()==3,"BLANK_URL_CONFIG_USES_LEGACY_DEFAULTS");
    check(probe_urls(" http://10.0.0.1/a , , http://10.0.0.2/b ").size()==2,"URL_CONFIG_TRIM_AND_EMPTY_ITEMS");
    for(auto url:{"file:///etc/passwd","ftp://example.com/x","http://u:p@example.com/x","http://127.0.0.1/x","http://169.254.1.1/x","http://example.com/x#f"})rejects([&]{safe_url(url);});
    check(safe_url("http://10.0.0.1:7001/auth.cgi")=="http://10.0.0.1:7001/auth.cgi","CAMPUS_PRIVATE_ENDPOINT_ALLOWED");
    for(auto xml:{"<r><ticket/></r>","<r><ticket>a</ticket><ticket>b</ticket></r>","<r><x><ticket>a</ticket></x></r>","<r><ticket><x>a</x></ticket></r>","<!DOCTYPE r [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><r><ticket>&x;</ticket></r>"})rejects([&]{response_ticket(xml);});
    check(response_ticket("<r><ticket><![CDATA[a&b]]></ticket></r>")=="a&b","CDATA_TICKET");
    for(auto value:{"0","-1","1x","9999999999999999999999999999"})rejects([&]{response_keep(std::string("<r><interval>")+value+"</interval></r>");});
    check(response_keep("<r><interval>1</interval></r>")==Seconds(5),"INTERVAL_LOWER_BOUND");
    std::string block="<!--//config.campus.js.chinatelecom.com<settings><auth-url>http://10.0.0.1/auth.cgi</auth-url><ticket-url>http://10.0.0.1/ticket.cgi?wlanuserip=10.0.0.2&wlanacip=10.0.0.3</ticket-url></settings>//config.campus.js.chinatelecom.com-->";
    auto ep=*portal_config(block);check(ep.user_ip=="10.0.0.2"&&ep.ac_ip=="10.0.0.3","LEGACY_QUERY_AMPERSAND");
    rejects([&]{portal_config(block+block);});
    auto duplicate=block;auto pos=duplicate.find("</settings>");duplicate.insert(pos,"<auth-url>http://10.0.0.1/auth.cgi</auth-url>");check(portal_config(duplicate).has_value(),"IDENTICAL_AUTH_DUPLICATE");
    duplicate=block;duplicate.insert(pos,"<auth-url>http://10.0.0.8/auth.cgi</auth-url>");rejects([&]{portal_config(duplicate);});
    Server server;Session session(server,ep,{"synthetic-client","02:00:00:00:00:01","2026-10-29 00:00:00"});
    unsigned times=0;session.on_time([&]{return "synthetic-time-"+std::to_string(++times);});
    rejects([&]{session.keep();});session.initialize();auto login=session.login("u<&","synthetic-password");
    check(login.keep=="http://10.0.0.9/keep.cgi","DYNAMIC_KEEP_PRESERVED");check(session.keep()==Seconds(240),"FIRST_KEEP");
    check(server.last_url==login.keep,"USE_LOGIN_KEEP_ENDPOINT");session.terminate();check(server.last_url==login.term,"USE_LOGIN_TERM_ENDPOINT");
    const auto first_cipher_output=server.cipher.encrypt("synthetic-isolation-check");
    Server next_server("066474E5-503E-4B82-98C4-DF4483DAF0B5");next_server.expected_client="second-session";
    Session next_session(next_server,ep,{"second-session","02:00:00:00:00:02","unused"});unsigned next_times=0;
    next_session.on_time([&]{return "synthetic-time-"+std::to_string(++next_times);});
    next_session.initialize();next_session.login("u<&","synthetic-password");
    check(next_session.keep()==Seconds(240)&&next_server.calls==4,"NEW_GENERATION_REFETCHES_MODULE_AND_USES_CHANGED_ALGORITHM");
    check(server.calls==5&&server.cipher.encrypt("synthetic-isolation-check")==first_cipher_output,"NEW_FACTORY_DOES_NOT_MUTATE_PREVIOUS_SESSION_STATE");
    Server invalid_server;invalid_server.invalid_keep=true;Session invalid_session(invalid_server,ep,{"synthetic-client","02:00:00:00:00:01","unused"});
    unsigned bad_times=0;invalid_session.on_time([&]{return "synthetic-time-"+std::to_string(++bad_times);});
    invalid_session.initialize();invalid_session.login("u<&","synthetic-password");
    bool classified=false;try{invalid_session.keep();}catch(const Failure& e){classified=std::string(e.what())=="HEARTBEAT_PROTOCOL_INVALID";}
    check(classified,"PROTOCOL_FAILURE_DISTINCT_FROM_TRANSPORT_FOR_CONSERVATIVE_PLANNER");
    Server stale_server;Session stale(stale_server,ep,{"synthetic-client","02:00:00:00:00:01","unused"});
    unsigned callbacks=0;stale.on_progress([&]{if(++callbacks==2)throw Failure("AUTH_GENERATION_CANCELLED",false);});
    rejects([&]{stale.initialize();});check(stale_server.calls==1,"CANCELLED_RESPONSE_CANNOT_START_TICKET_OR_LOGIN");
    Script unknown;unknown.responses={{200,std::string(4,'\0')+char(36)+"FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF",{}}};
    Session unsupported(unknown,ep,{"synthetic-client","02:00:00:00:00:01","unused"});
    bool unsupported_classified=false;try{unsupported.initialize();}catch(const Failure& e){unsupported_classified=std::string(e.what())=="ALGORITHM_UNSUPPORTED"&&e.deterministic;}
    check(unsupported_classified&&unknown.responses.empty(),"UNKNOWN_ALGORITHM_EXPLICIT_DETERMINISTIC_WITHOUT_INTERNAL_REFETCH");
    Policy bounded_policy;bounded_policy.max_recoveries=30;Core bounded(bounded_policy);const Mono zero{};
    for(int i=0;i<3;++i){auto g=*bounded.authorize(zero+Seconds(i*100));bounded.failed(g,"ALGORITHM_UNSUPPORTED",zero+Seconds(i*100),true);}
    check(bounded.snapshot().phase==Phase::paused&&bounded.snapshot().recovery_attempts==3,"REPEATED_UNKNOWN_ALGORITHM_STOPS_BEFORE_GENERAL_30_RECOVERY_LIMIT");
    Script script;script.responses={{200,"<html>not online</html>",{}},{204,"",{}}};check(probe(script,{"http://10.0.0.1/a","http://10.0.0.1/b"}).kind==Probe::online,"UNKNOWN_200_NOT_ONLINE");
    script.responses={{302,"","/portal"}};check(probe(script,{"http://10.0.0.1/start"}).portal_url=="http://10.0.0.1/portal","RELATIVE_REDIRECT");
    const std::string candidate="http://10.0.0.1/portal?wlanuserip=10.0.0.2&wlanacip=10.0.0.3";
    script.responses={{200,block,{}}};auto recovered=discover(script,{Probe::portal,candidate,duplicate});
    check(recovered.user_ip=="10.0.0.2"&&recovered.auth==ep.auth,"BAD_CACHED_CONFIG_MAY_RECOVER_ONLY_FROM_FRESH_VALID_CONFIG");
    script.responses={{200,"ordinary page",{}}};rejects([&]{discover(script,{Probe::portal,candidate,duplicate});});
    script.responses={{302,"","/a"},{302,"","/b"},{302,"","/c"}};
    bool redirect_limit=false;try{discover(script,{Probe::portal,candidate,{}});}catch(const Failure& e){redirect_limit=std::string(e.what())=="PORTAL_REDIRECT_LIMIT";}
    check(redirect_limit&&script.responses.empty(),"PORTAL_REDIRECT_LIMIT_THREE_HOPS");
    const auto timeout_count=script.timeouts.size();
    check(timeout_count>=3&&script.timeouts[timeout_count-1]+script.timeouts[timeout_count-2]+script.timeouts[timeout_count-3]<Policy{}.operation_timeout.count(),"PORTAL_REDIRECT_CHAIN_FITS_AUTH_PROGRESS_BUDGET");
    script.responses={{200,"ordinary page",{}}};rejects([&]{discover(script,{Probe::portal,candidate+"&wlanuserip=10.0.0.9",{}});});
    script.responses={{200,"ordinary page",{}}};auto fallback=discover(script,{Probe::portal,candidate,{}});
    check(fallback.source=="legacy_fallback"&&fallback.user_ip=="10.0.0.2"&&fallback.ticket.find("wlanacip=10.0.0.3")!=fallback.ticket.npos,"BOUNDED_LEGACY_ENDPOINT_FALLBACK_KEEPS_CANDIDATE_IPS");
    std::cout<<checks<<" protocol checks passed; synthetic server, not native equivalence\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
