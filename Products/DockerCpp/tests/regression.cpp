#include "esurfing/state_store.hpp"
#include "esurfing/config.hpp"
#include <fstream>
#include <iostream>
#include <functional>
#include <vector>
#include <ctime>

using namespace esurfing;
static unsigned assertions = 0;
void check(bool pass, const char* name) { ++assertions; if(!pass) throw std::runtime_error(name); }
int main() {
    try {
        const Mono zero{};
        check(sizeof(std::time_t)>=8,"64-bit time ABI on every built platform");
        std::time_t future=static_cast<std::time_t>(2147483648LL);std::tm converted{};
#ifdef _WIN32
        const bool converted_ok=gmtime_s(&converted,&future)==0;
#else
        const bool converted_ok=gmtime_r(&future,&converted)!=nullptr;
#endif
        check(converted_ok&&converted.tm_year==138&&converted.tm_mon==0&&converted.tm_mday==19&&converted.tm_hour==3&&converted.tm_min==14&&converted.tm_sec==8,"2038 boundary roundtrip without changing the system clock");
        auto config=RuntimeConfig::read([](const char*){return std::string{};});
        check(config.policy.max_recoveries==30&&config.probe_budget==Seconds(60)&&config.auto_reauth_enabled,"legacy runtime defaults");
        config=RuntimeConfig::read([](const char* key){return std::string(key)=="HEARTBEAT_INTERVAL_MAX_SECONDS"?std::string("9999"):std::string("bad");});
        check(config.heartbeat_max==Seconds(600)&&config.confirmation_attempts==3,"clamp and malformed env fallback");
        Core c;
        c.network(true);
        check(c.snapshot().phase == Phase::external_online && !c.snapshot().authenticated, "external connectivity must not claim session");
        c.tick(zero + Seconds(10000));
        check(c.snapshot().recovery_attempts == 0, "no restart for externally online");
        auto g = *c.authorize(zero);
        check(!c.authorize(zero), "no concurrent authorization");
        check(c.login_accepted(g, zero), "login response accepted");
        check(!c.snapshot().authenticated, "first keep gates authentication");
        check(c.keep_confirmed(g, Seconds(240), zero), "first keep confirms");
        check(!c.heartbeat_due(zero + Seconds(239)) && c.heartbeat_due(zero + Seconds(240)), "monotonic heartbeat deadline");
        c.heartbeat_failed(g, zero + Seconds(240));
        check(c.snapshot().authenticated && !c.heartbeat_due(zero + Seconds(244)), "transient probe/transport failure keeps session");
        c.heartbeat_failed(g, zero + Seconds(245));
        c.heartbeat_failed(g, zero + Seconds(255));
        check(!c.snapshot().authenticated && c.snapshot().phase == Phase::backoff, "threshold loses owned session");
        check(!c.keep_confirmed(g, Seconds(240), zero + Seconds(256)), "stale keep cannot revive generation");
        for(unsigned i=0; i<2; ++i) {
            auto now = zero + Seconds(1000 + i*1000);
            g = *c.authorize(now);
            c.login_accepted(g, now);
            c.keep_confirmed(g, Seconds(240), now);
            check(c.snapshot().recovery_attempts == i+1, "brief healthy does not reset budget");
            c.failed(g, "TRANSIENT", now + Seconds(1));
        }
        check(c.snapshot().phase == Phase::paused && !c.authorize(zero + Seconds(9000)), "flapping bounded by recovery limit");
        c.resume();
        g = *c.authorize(zero);
        c.stop();
        check(!c.login_accepted(g, zero) && !c.keep_confirmed(g, Seconds(5), zero), "stop cancels every old callback");
        check(!c.authorize(zero), "stop prevents automatic recovery");
        c.resume();
        g = *c.authorize(zero);
        c.tick(zero + Seconds(90));
        check(c.snapshot().error == "AUTH_OPERATION_TIMEOUT", "bounded operation progress");
        g = *c.authorize(zero + Seconds(100));
        c.login_accepted(g, zero + Seconds(100)); c.keep_confirmed(g, Seconds(5), zero + Seconds(100));
        c.keep_confirmed(g, Seconds(240), zero + Seconds(110));
        c.tick(zero + Seconds(220));
        check(c.snapshot().recovery_attempts == 0, "sustained health resets budget");
        Core invalid;
        g = *invalid.authorize(zero); invalid.login_accepted(g, zero);
        check(!invalid.keep_confirmed(g, Seconds(0), zero) && !invalid.snapshot().authenticated, "invalid keep interval cannot confirm");
        invalid.resume();
        Core protocol_failure;auto pg=*protocol_failure.authorize(zero);protocol_failure.login_accepted(pg,zero);protocol_failure.keep_confirmed(pg,Seconds(5),zero);
        protocol_failure.heartbeat_failed(pg,zero+Seconds(5),true);
        check(protocol_failure.snapshot().error=="HEARTBEAT_PROTOCOL_INVALID"&&protocol_failure.snapshot().authenticated,"protocol failure is not mislabeled as transport or instant session loss");
        check(json_quote("\"\\\n") == "\"\\\"\\\\\\u000a\"", "valid escaped json");
        auto directory = std::filesystem::temp_directory_path() / ("esurfing-native-test-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
        std::filesystem::create_directories(directory);
        {
            StateStore store(directory);
            bool rejected = false;
            try { StateStore second(directory); } catch(const std::exception&) { rejected = true; }
            check(rejected, "single state directory owner");
            store.write(c.snapshot(), true, 1700000000);
            store.write(c.snapshot(), false, 1700000001);
            std::ifstream in(directory / "health.json");
            std::string body((std::istreambuf_iterator<char>(in)), {});
            check(body.find("\"processAlive\": false") != std::string::npos && body.find(store.instance()) != std::string::npos, "atomic replace retains instance and stop state");
            check(std::distance(std::filesystem::directory_iterator(directory), std::filesystem::directory_iterator{}) == 2, "no leftover temporary files");
        }
        { StateStore next(directory); check(!next.instance().empty(), "ownership released only on destructor"); }
        auto secret_path=directory/"synthetic.secret";
        {std::ofstream out(secret_path,std::ios::binary);out<<"synthetic-password\r\nignored second line";}
        check(read_password_file(secret_path)=="synthetic-password","Windows CRLF secret file does not add carriage return to password");
        {std::ofstream out(secret_path,std::ios::binary);out<<std::string(4096,'x')<<"\r\n";}
        check(read_password_file(secret_path).size()==4096,"bounded secret file accepts full limit plus CRLF");
        {std::ofstream out(secret_path,std::ios::binary);out<<std::string(4098,'x');}
        bool large_rejected=false;try{read_password_file(secret_path);}catch(const std::exception& e){large_rejected=std::string(e.what())=="PASSWORD_TOO_LARGE";}
        check(large_rejected,"oversized secret rejected during bounded read");
        bool wrong_type=false;try{read_password_file(directory);}catch(const std::exception& e){wrong_type=std::string(e.what())=="PASSWORD_FILE_UNREADABLE";}
        check(wrong_type,"secret source must be readable regular file");
        std::filesystem::remove_all(directory);
        std::cout << assertions << " assertions passed; offline state/ownership tests only\n";
        return 0;
    } catch(const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
