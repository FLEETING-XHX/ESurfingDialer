#pragma once
#include "protocol.hpp"
#include <atomic>
#include <map>
#include <optional>

namespace esurfing {
struct HttpRequest {
    std::string url;
    std::optional<std::string> body;
    std::map<std::string,std::string> headers;
    std::size_t limit=128*1024;
    long timeout_seconds=45;
};
struct HttpResponse { long status; std::string body, location; };
struct Http {
    virtual ~Http()=default;
    virtual HttpResponse perform(const HttpRequest&)=0;
};
class CurlHttp:public Http {
    std::atomic_bool& stop_;
    bool allow_loopback_;
public:
    explicit CurlHttp(std::atomic_bool& stop,bool allow_loopback=false):stop_(stop),allow_loopback_(allow_loopback){}
    HttpResponse perform(const HttpRequest&) override;
};
struct Probe {
    enum Kind { online, portal, error } kind=error;
    std::string portal_url, portal_body;
};
Probe probe(Http&,const std::vector<std::string>& urls,bool allow_loopback=false,Seconds budget=Seconds(60));
std::vector<std::string> probe_urls(std::string_view configured,bool allow_loopback=false);
Endpoints discover(Http&,const Probe&,bool allow_loopback=false);
}
