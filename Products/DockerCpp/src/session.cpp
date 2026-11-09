#include "esurfing/session.hpp"
#include "esurfing/module_loader.hpp"
#include <cstdlib>
#include <algorithm>

namespace esurfing {
std::unique_ptr<Cipher> load_session_cipher(std::span<const std::uint8_t> module) {
    const auto configured=std::getenv("NATIVE_MODULE_LIBRARY");
    const auto library=read_known_native_library(configured?configured:"/app/module-profiles/android32-2082-loader.so");
    auto material=decode_module(module,library);
    if(material.cipher==ModuleCipher::sm4_cbc)return std::make_unique<Cipher>(material.algorithm_id,material.sm4_key,material.sm4_iv);
    return std::make_unique<Cipher>(material.algorithm_id,material.key,material.iv);
}
Session::Session(Http& http,Endpoints endpoints,Identity identity,bool local,Seconds maximum,CipherFactory factory)
    :http_(http),endpoints_(std::move(endpoints)),identity_(std::move(identity)),local_(local),heartbeat_max_(maximum),
     cipher_factory_(factory?std::move(factory):CipherFactory(load_session_cipher)) {
    endpoints_.auth=safe_url(endpoints_.auth,local);endpoints_.ticket=safe_url(endpoints_.ticket,local);
    if(!ipv4(endpoints_.user_ip)||!ipv4(endpoints_.ac_ip))throw Failure("SESSION_IP_INVALID");
}
std::string Session::post(const std::string& url,const std::string& data,std::size_t limit) {
    if(progress_)progress_();
    HttpRequest req{url};req.body=data;req.limit=limit;
    req.diagnostic={operation_,next_request_attempt()};last_diagnostic_=req.diagnostic;
    req.headers={{"CDC-Checksum",checksum(data)},{"Client-ID",identity_.client_id},{"Algo-ID",algorithm_}};
    auto response=http_.perform(req);
    last_diagnostic_=response.diagnostic.attempt?response.diagnostic:req.diagnostic;
    last_diagnostic_.http_status=response.status;
    if(progress_)progress_(); // Reject a stale generation/network before consuming its response.
    if(response.status<200||response.status>=300)throw Failure("AUTH_HTTP_ERROR",false,last_diagnostic_);
    if(response.body.empty())throw Failure("AUTH_BODY_EMPTY",true,last_diagnostic_);
    if(response.body.size()>limit)throw Failure("AUTH_BODY_TOO_LARGE",true,last_diagnostic_);
    return response.body;
}
std::string Session::encrypted_post(const std::string& url,const std::vector<std::pair<std::string,std::string>>& fields) {
    if(!cipher_)throw Failure("SESSION_MISSING");
    const auto response=post(url,encrypt_request(fields));
    try{return cipher_->decrypt(response);}
    catch(const std::exception& error){
        const std::string code=error.what();
        for(const auto* known:{"CIPHERTEXT_SIZE_INVALID","CIPHERTEXT_HEX_INVALID","CIPHERTEXT_PADDING_INVALID","PLAINTEXT_NUL_INVALID","PLAINTEXT_SIZE_INVALID","DECRYPT_FAILED"})
            if(code==known)throw Failure(known);
        throw;
    }
}
std::string Session::encrypt_request(const std::vector<std::pair<std::string,std::string>>& fields) {
    auto xml=request_xml(fields);
    if(cipher_->uses_native_xml_profile()) {
        const auto time=std::find_if(fields.begin(),fields.end(),[](const auto& f){return f.first=="local-time";});
        if(time==fields.end())throw Failure("REQUEST_TIME_INVALID");
        xml=normalize_native_request_xml(xml,time->second);
    }
    return cipher_->encrypt(xml);
}
std::vector<std::pair<std::string,std::string>> Session::common(bool with_ticket) const {
    std::vector<std::pair<std::string,std::string>> f={{"user-agent",docker_user_agent},{"client-id",identity_.client_id},{"local-time",timestamp()},{"host-name","Xiaomi 6"},{"ipv4",endpoints_.user_ip}};
    if(with_ticket)f.emplace_back("ticket",ticket_);
    f.emplace_back("ipv6","");f.emplace_back("mac",identity_.mac);f.emplace_back("ostag","Xiaomi 6");return f;
}
void Session::initialize() {
    operation_=Operation::bootstrap;
    if(cipher_)throw Failure("SESSION_ALREADY_INITIALIZED");
    auto module=post(endpoints_.ticket,algorithm_,1024*1024);
    auto bytes=std::span(reinterpret_cast<const std::uint8_t*>(module.data()),module.size());
    if(bootstrap_observer_)bootstrap_observer_(bytes);
    try{
        auto selected=cipher_factory_(bytes);
        if(!selected)throw Failure("CIPHER_INIT_FAILED");
        if(progress_)progress_();
        algorithm_=selected->algorithm_id();cipher_=std::move(selected);
    }catch(const ModuleError& error){
        throw Failure(error.what());
    }catch(const std::exception& error){
        const std::string code=error.what();
        for(const auto* known:{"BOOTSTRAP_SIZE_INVALID","ZSM_HEADER_INVALID","ZSM_ALGORITHM_UNSUPPORTED","ALGORITHM_ID_INVALID","ALGORITHM_UNSUPPORTED","CIPHER_INIT_FAILED"})
            if(code==known)throw Failure(known);
        throw;
    }
    operation_=Operation::ticket;
    ticket_=response_ticket(encrypted_post(endpoints_.ticket,common(false)));
}
Login Session::login(const std::string& user,const std::string& password) {
    operation_=Operation::login;
    if(!cipher_||ticket_.empty())throw Failure("SESSION_MISSING");
    login_=response_login(encrypted_post(endpoints_.auth,{{"user-agent",docker_user_agent},{"client-id",identity_.client_id},{"local-time",timestamp()},{"ticket",ticket_},{"userid",user},{"passwd",password}}),local_,heartbeat_max_);
    return *login_;
}
Seconds Session::keep() {
    operation_=Operation::keep;
    if(!login_)throw Failure("LOGIN_REQUIRED");
    auto response=encrypted_post(login_->keep,common(true));
    try{return response_keep(response,heartbeat_max_);}
    catch(const Failure&){throw Failure("HEARTBEAT_PROTOCOL_INVALID");}
}
void Session::terminate(Http* cleanup_transport) {
    operation_=Operation::term;
    if(!login_)return;
    HttpRequest req{login_->term};req.body=encrypt_request(common(true));req.timeout_seconds=3;
    req.diagnostic={operation_,next_request_attempt()};last_diagnostic_=req.diagnostic;
    req.headers={{"CDC-Checksum",checksum(*req.body)},{"Client-ID",identity_.client_id},{"Algo-ID",algorithm_}};
    auto response=(cleanup_transport?*cleanup_transport:http_).perform(req);
    last_diagnostic_=response.diagnostic.attempt?response.diagnostic:req.diagnostic;last_diagnostic_.http_status=response.status;
    if(response.status<200||response.status>=300)throw Failure("TERM_HTTP_ERROR",false,last_diagnostic_);
}
}
