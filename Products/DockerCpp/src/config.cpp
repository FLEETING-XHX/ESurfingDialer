#include "esurfing/config.hpp"
#include <algorithm>
#include <charconv>
#include <fstream>

namespace esurfing {
std::string read_password_file(const std::filesystem::path& path){
    std::error_code ec;
    if(!std::filesystem::is_regular_file(path,ec))throw std::runtime_error("PASSWORD_FILE_UNREADABLE");
    std::ifstream in(path,std::ios::binary);if(!in)throw std::runtime_error("PASSWORD_FILE_UNREADABLE");
    std::string value;char c;
    while(in.get(c)&&c!='\n'){
        if(value.size()>=4097)throw std::runtime_error("PASSWORD_TOO_LARGE");
        value+=c;
    }
    if(!in&&!in.eof())throw std::runtime_error("PASSWORD_FILE_UNREADABLE");
    if(!value.empty()&&value.back()=='\r')value.pop_back();
    if(value.size()>4096)throw std::runtime_error("PASSWORD_TOO_LARGE");
    return value;
}
RuntimeConfig RuntimeConfig::read(const std::function<std::string(const char*)>& env) {
    auto number=[&](const char* key,long long fallback,long long min,long long max){
        auto text=env(key);auto start=text.find_first_not_of(" \r\n\t");if(start==text.npos)return fallback;
        text=text.substr(start,text.find_last_not_of(" \r\n\t")-start+1);
        if(text.starts_with('+'))text.erase(0,1);
        long long value=0;auto [p,e]=std::from_chars(text.data(),text.data()+text.size(),value);
        if(e!=std::errc{}||p!=text.data()+text.size())return fallback;
        return std::clamp(value,min,max);
    };
    RuntimeConfig c;
    c.policy.max_recoveries=static_cast<unsigned>(number("MAX_RECOVERIES_BEFORE_EXIT",30,3,1000));
    c.policy.heartbeat_failure_limit=static_cast<unsigned>(number("HEARTBEAT_FAILURE_THRESHOLD",3,1,20));
    c.policy.retry_initial=Seconds(number("LOGIN_RETRY_INITIAL_SECONDS",5,1,600));
    c.policy.retry_max=Seconds(number("LOGIN_RETRY_MAX_SECONDS",60,5,1800));
    if(c.policy.retry_max<c.policy.retry_initial)c.policy.retry_max=c.policy.retry_initial;
    c.probe_budget=Seconds(number("NETWORK_PROBE_BUDGET_SECONDS",60,1,180));
    c.network_interval=Seconds(number("NETWORK_CHECK_INTERVAL_SECONDS",5,1,300));
    c.health_interval=Seconds(number("HEALTH_WRITE_INTERVAL_SECONDS",10,1,300));
    c.heartbeat_max=Seconds(number("HEARTBEAT_INTERVAL_MAX_SECONDS",240,30,600));
    c.post_login_grace=Seconds(number("POST_LOGIN_PORTAL_GRACE_SECONDS",20,5,300));
    c.portal_cooldown=Seconds(number("PORTAL_REAUTH_COOLDOWN_SECONDS",300,30,7200));
    c.confirmation_interval=Seconds(number("LOGIN_CONFIRMATION_INTERVAL_SECONDS",2,1,30));
    c.portal_threshold=static_cast<unsigned>(number("PORTAL_DETECTION_THRESHOLD",12,1,120));
    c.confirmation_attempts=static_cast<unsigned>(number("LOGIN_CONFIRMATION_ATTEMPTS",3,1,10));
    c.auto_reauth_enabled=number("AUTO_REAUTH_ENABLED",1,0,1)!=0;
    c.reauth_start_hour=static_cast<unsigned>(number("AUTO_REAUTH_SAFE_WINDOW_START_HOUR",4,0,23));
    c.reauth_end_hour=static_cast<unsigned>(number("AUTO_REAUTH_SAFE_WINDOW_END_HOUR",6,0,24));
    c.policy.validate();return c;
}
}
