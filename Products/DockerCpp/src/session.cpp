#include "esurfing/session.hpp"

namespace esurfing {
Session::Session(Http& http,Endpoints endpoints,Identity identity,bool local,Seconds maximum)
    :http_(http),endpoints_(std::move(endpoints)),identity_(std::move(identity)),local_(local),heartbeat_max_(maximum) {
    endpoints_.auth=safe_url(endpoints_.auth,local);endpoints_.ticket=safe_url(endpoints_.ticket,local);
    if(!ipv4(endpoints_.user_ip)||!ipv4(endpoints_.ac_ip))throw Failure("SESSION_IP_INVALID");
}
std::string Session::post(const std::string& url,const std::string& data,std::size_t limit) {
    if(progress_)progress_();
    HttpRequest req{url};req.body=data;req.limit=limit;
    req.headers={{"CDC-Checksum",checksum(data)},{"Client-ID",identity_.client_id},{"Algo-ID",algorithm_}};
    auto response=http_.perform(req);
    if(progress_)progress_(); // Reject a stale generation/network before consuming its response.
    if(response.status<200||response.status>=300)throw Failure("AUTH_HTTP_ERROR",false);
    if(response.body.empty())throw Failure("AUTH_BODY_EMPTY");
    if(response.body.size()>limit)throw Failure("AUTH_BODY_TOO_LARGE");
    return response.body;
}
std::string Session::encrypted_post(const std::string& url,const std::vector<std::pair<std::string,std::string>>& fields) {
    if(!cipher_)throw Failure("SESSION_MISSING");
    return cipher_->decrypt(post(url,cipher_->encrypt(request_xml(fields))));
}
std::vector<std::pair<std::string,std::string>> Session::common(bool with_ticket) const {
    std::vector<std::pair<std::string,std::string>> f={{"user-agent",docker_user_agent},{"client-id",identity_.client_id},{"local-time",timestamp()},{"host-name","Xiaomi 6"},{"ipv4",endpoints_.user_ip}};
    if(with_ticket)f.emplace_back("ticket",ticket_);
    f.emplace_back("ipv6","");f.emplace_back("mac",identity_.mac);f.emplace_back("ostag","Xiaomi 6");return f;
}
void Session::initialize() {
    if(cipher_)throw Failure("SESSION_ALREADY_INITIALIZED");
    auto module=post(endpoints_.ticket,algorithm_,1024*1024);
    try{
        algorithm_=module_candidate(std::span(reinterpret_cast<const std::uint8_t*>(module.data()),module.size()));
        cipher_=std::make_unique<Cipher>(algorithm_);
    }catch(const std::exception& error){
        const std::string code=error.what();
        for(const auto* known:{"BOOTSTRAP_SIZE_INVALID","ZSM_HEADER_INVALID","ZSM_ALGORITHM_UNSUPPORTED","ALGORITHM_ID_INVALID","ALGORITHM_UNSUPPORTED","CIPHER_INIT_FAILED"})
            if(code==known)throw Failure(known);
        throw;
    }
    ticket_=response_ticket(encrypted_post(endpoints_.ticket,common(false)));
}
Login Session::login(const std::string& user,const std::string& password) {
    if(!cipher_||ticket_.empty())throw Failure("SESSION_MISSING");
    login_=response_login(encrypted_post(endpoints_.auth,{{"user-agent",docker_user_agent},{"client-id",identity_.client_id},{"local-time",timestamp()},{"ticket",ticket_},{"userid",user},{"passwd",password}}),local_,heartbeat_max_);
    return *login_;
}
Seconds Session::keep() {
    if(!login_)throw Failure("LOGIN_REQUIRED");
    auto response=encrypted_post(login_->keep,common(true));
    try{return response_keep(response,heartbeat_max_);}
    catch(const Failure&){throw Failure("HEARTBEAT_PROTOCOL_INVALID");}
}
void Session::terminate(Http* cleanup_transport) {
    if(!login_)return;
    HttpRequest req{login_->term};req.body=cipher_->encrypt(request_xml(common(true)));req.timeout_seconds=3;
    req.headers={{"CDC-Checksum",checksum(*req.body)},{"Client-ID",identity_.client_id},{"Algo-ID",algorithm_}};
    auto response=(cleanup_transport?*cleanup_transport:http_).perform(req);
    if(response.status<200||response.status>=300)throw Failure("TERM_HTTP_ERROR",false);
}
}
