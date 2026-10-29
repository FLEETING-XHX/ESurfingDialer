#include "esurfing/http.hpp"
#include <curl/curl.h>
#include <memory>
#include <limits>
#include <cctype>

namespace esurfing {
struct CurlRuntime { CurlRuntime(){if(curl_global_init(CURL_GLOBAL_DEFAULT))throw Failure("CURL_INIT_FAILED");} ~CurlRuntime(){curl_global_cleanup();} };
struct Transfer { HttpResponse response{};std::size_t limit,headers=0;bool failed=false,invalid_headers=false; };
static size_t receive(char* bytes,size_t size,size_t n,void* context) noexcept {
    auto& t=*static_cast<Transfer*>(context);
    if(size&&n>std::numeric_limits<size_t>::max()/size){t.failed=true;return 0;}
    auto length=size*n;
    if(length>t.limit-t.response.body.size()){t.failed=true;return 0;}
    try{t.response.body.append(bytes,length);return length;}catch(...){t.failed=true;return 0;}
}
static size_t header(char* bytes,size_t size,size_t n,void* context) noexcept {
    auto& t=*static_cast<Transfer*>(context);
    if(size&&n>std::numeric_limits<size_t>::max()/size){t.invalid_headers=true;return 0;}
    auto length=size*n;if(length>32768-t.headers){t.invalid_headers=true;return 0;}t.headers+=length;
    try {
        std::string line(bytes,length),name=line.substr(0,line.find(':'));
        for(char& c:name)c=static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        if(name=="location") {auto value=line.substr(name.size()+1);auto a=value.find_first_not_of(" \t");auto b=value.find_last_not_of(" \r\n\t");
            if(a!=value.npos){auto location=value.substr(a,b-a+1);
                if(!t.response.location.empty()&&location!=t.response.location){t.invalid_headers=true;return 0;}
                t.response.location=std::move(location);}}
        return length;
    }catch(...){return 0;}
}
HttpResponse CurlHttp::perform(const HttpRequest& request) {
    static CurlRuntime runtime;
    if(stop_.load())throw Failure("CANCELLED",false);
    auto url=safe_url(request.url,allow_loopback_);
    if(request.limit>1024*1024||request.timeout_seconds<1||request.timeout_seconds>90)throw Failure("HTTP_POLICY_INVALID");
    auto curl=std::unique_ptr<CURL,decltype(&curl_easy_cleanup)>(curl_easy_init(),curl_easy_cleanup);
    if(!curl)throw Failure("CURL_INIT_FAILED");
    Transfer t; t.limit=request.limit;
    auto set=[&](auto option,auto value){if(curl_easy_setopt(curl.get(),option,value)!=CURLE_OK)throw Failure("CURL_OPTION_FAILED");};
    set(CURLOPT_URL,url.c_str());set(CURLOPT_FOLLOWLOCATION,0L);set(CURLOPT_NOSIGNAL,1L);
    set(CURLOPT_PROTOCOLS_STR,"http,https");set(CURLOPT_CONNECTTIMEOUT,std::min(10L,request.timeout_seconds));set(CURLOPT_TIMEOUT,request.timeout_seconds);
    set(CURLOPT_SSL_VERIFYPEER,1L);set(CURLOPT_SSL_VERIFYHOST,2L);
    set(CURLOPT_USERAGENT,docker_user_agent);set(CURLOPT_WRITEFUNCTION,receive);set(CURLOPT_WRITEDATA,&t);
    if(allow_loopback_)set(CURLOPT_PROXY,""); // Isolated fixture traffic must never use an inherited proxy.
    set(CURLOPT_HEADERFUNCTION,header);set(CURLOPT_HEADERDATA,&t);
    set(CURLOPT_NOPROGRESS,0L);set(CURLOPT_XFERINFODATA,&stop_);
    set(CURLOPT_XFERINFOFUNCTION,+[](void* p,curl_off_t,curl_off_t,curl_off_t,curl_off_t)->int {return static_cast<std::atomic_bool*>(p)->load()?1:0;});
    curl_slist* headers=nullptr;
    auto append=[&](const std::string& v){auto next=curl_slist_append(headers,v.c_str());if(!next){curl_slist_free_all(headers);headers=nullptr;throw Failure("HTTP_HEADERS_FAILED");}headers=next;};
    try {
        append("Accept: text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*");
        if(request.body)append("Content-Type: application/x-www-form-urlencoded");
        for(auto& [name,value]:request.headers) {
            if(name.empty()||name.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-")!=name.npos
               ||value.find_first_of("\r\n")!=value.npos||value.find('\0')!=value.npos)throw Failure("HTTP_HEADER_INVALID");
            append(name+": "+value);
        }
    }catch(...){curl_slist_free_all(headers);throw;}
    auto holder=std::unique_ptr<curl_slist,decltype(&curl_slist_free_all)>(headers,curl_slist_free_all);
    set(CURLOPT_HTTPHEADER,headers);
    if(request.body){set(CURLOPT_POST,1L);set(CURLOPT_POSTFIELDS,request.body->data());set(CURLOPT_POSTFIELDSIZE_LARGE,static_cast<curl_off_t>(request.body->size()));}
    auto code=curl_easy_perform(curl.get());
    if(stop_.load())throw Failure("CANCELLED",false);
    if(t.failed)throw Failure("HTTP_BODY_TOO_LARGE");
    if(t.invalid_headers)throw Failure("HTTP_HEADERS_INVALID");
    if(code!=CURLE_OK)throw Failure("HTTP_TRANSPORT_ERROR",false);
    curl_easy_getinfo(curl.get(),CURLINFO_RESPONSE_CODE,&t.response.status);
    return std::move(t.response);
}
std::vector<std::string> probe_urls(std::string_view configured,bool local){
    std::vector<std::string> urls;
    for(std::size_t at=0;at<configured.size();){
        auto end=configured.find(',',at);if(end==configured.npos)end=configured.size();auto item=configured.substr(at,end-at);
        auto first=item.find_first_not_of(" \r\n\t");
        if(first!=item.npos)urls.push_back(safe_url(item.substr(first,item.find_last_not_of(" \r\n\t")-first+1),local));
        at=end+1;
    }
    if(urls.empty())urls={"http://www.gstatic.com/generate_204","http://connect.rom.miui.com/generate_204","http://www.msftconnecttest.com/connecttest.txt"};
    return urls;
}
Probe probe(Http& http,const std::vector<std::string>& urls,bool local,Seconds budget) {
    const auto end=std::chrono::steady_clock::now()+budget;
    for(auto& url:urls) {
        auto remaining=std::chrono::duration_cast<Seconds>(end-std::chrono::steady_clock::now()).count();if(remaining<1)break;
        try {
            HttpRequest request{url};request.limit=128*1024;request.timeout_seconds=std::min<long>(10,remaining);
            auto r=http.perform(request);
            if(r.status>=300&&r.status<=399&&!r.location.empty())return {Probe::portal,resolve_url(url,r.location,local),{}};
            if(r.status==204&&r.body.empty())return {Probe::online,{},{}};
            if(r.status==200) {
                if(r.body.find("<!--//config.campus.js.chinatelecom.com")!=std::string::npos)return {Probe::portal,url,r.body};
                auto canonical=safe_url(url,local);
                if(canonical=="http://www.msftconnecttest.com/connecttest.txt"&&r.body=="Microsoft Connect Test")return {Probe::online,{},{}};
            }
        }catch(const Failure& e){if(std::string(e.what())=="CANCELLED")throw;}
    }return {};
}
Endpoints discover(Http& http,const Probe& observation,bool local) {
    std::optional<Failure> cached_failure;
    try{if(auto parsed=portal_config(observation.portal_body,local))return *parsed;}catch(const Failure& e){cached_failure=e;}
    std::string url=safe_url(observation.portal_url,local);
    for(unsigned hop=0;hop<3;++hop) {
        // Legacy portal discovery used its 10-second probe transport. A chain
        // of three general 45-second auth requests could outlive Core's budget.
        HttpRequest request{url};request.timeout_seconds=10;
        auto r=http.perform(request);
        if(r.status>=300&&r.status<=399){if(r.location.empty())throw Failure("PORTAL_REDIRECT_INVALID");url=resolve_url(url,r.location,local);continue;}
        if(r.status<200||r.status>=300)throw Failure("PORTAL_HTTP_ERROR",false);
        if(auto endpoints=portal_config(r.body,local))return *endpoints;
        if(cached_failure)throw *cached_failure;
        auto q=query_parameters(url);
        if(q["wlanuserip"].size()!=1||q["wlanacip"].size()!=1||!ipv4(q["wlanuserip"][0])||!ipv4(q["wlanacip"][0]))throw Failure("PORTAL_IP_INVALID");
        auto ticket="http://14.146.227.141:7001/ticket.cgi?wlanuserip="+q["wlanuserip"][0]+"&wlanacip="+q["wlanacip"][0]+"&portal_node=http%3A%2F%2F125.88.59.131%3A10002";
        return {"http://14.146.227.141:7001/auth.cgi",ticket,q["wlanuserip"][0],q["wlanacip"][0],"legacy_fallback"};
    }throw Failure("PORTAL_REDIRECT_LIMIT");
}
}
