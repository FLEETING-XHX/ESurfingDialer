#include "esurfing/portal_policy.hpp"
#include <algorithm>

namespace esurfing {
bool PortalPolicy::observe(const std::string& key,bool authenticated,unsigned failures,Mono now){
    if(key!=key_){key_=key;hits_=0;}
    hits_=std::min(hits_+1,threshold_);
    if(!authenticated)return true;
    if(auth_at_&&now-*auth_at_<=grace_&&!failures)return false;
    if(hits_<threshold_&&!failures)return false;
    return !requested_at_||now-*requested_at_>=cooldown_;
}
}
