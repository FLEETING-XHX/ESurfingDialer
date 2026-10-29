#pragma once
#include "http.hpp"
#include "cipher.hpp"
#include <functional>

namespace esurfing {
struct Identity { std::string client_id, mac, local_time; };
class Session {
    Http& http_;
    Endpoints endpoints_;
    Identity identity_;
    std::unique_ptr<Cipher> cipher_;
    std::string algorithm_="00000000-0000-0000-0000-000000000000",ticket_;
    std::optional<Login> login_;
    bool local_;
    Seconds heartbeat_max_;
    std::function<void()> progress_;
    std::function<std::string()> time_source_;
    std::string timestamp() const { return time_source_?time_source_():identity_.local_time; }
    std::string post(const std::string& url,const std::string& data,std::size_t limit=128*1024);
    std::string encrypted_post(const std::string& url,const std::vector<std::pair<std::string,std::string>>& fields);
    std::vector<std::pair<std::string,std::string>> common(bool with_ticket) const;
public:
    Session(Http& http,Endpoints endpoints,Identity identity,bool allow_loopback=false,Seconds heartbeat_max=Seconds(240));
    void on_progress(std::function<void()> callback) { progress_=std::move(callback); }
    void on_time(std::function<std::string()> callback) { time_source_=std::move(callback); }
    Session(const Session&)=delete;
    Session& operator=(const Session&)=delete;
    void initialize();
    Login login(const std::string& user,const std::string& password);
    Seconds keep();
    // A successful HTTP request is NOT proof of server-side logout confirmation.
    void terminate(Http* cleanup_transport=nullptr);
};
}
