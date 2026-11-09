#include "esurfing/session.hpp"
#include "esurfing/module_loader.hpp"
#include <algorithm>
#include <fstream>
#include <iostream>
#include <sstream>
#include <map>

using namespace esurfing;
namespace {
unsigned checks=0;
void check(bool ok,const char* label){++checks;if(!ok)throw std::runtime_error(label);}
std::string read(const char* path){std::ifstream f(path,std::ios::binary);if(!f)throw std::runtime_error("FIXTURE_UNAVAILABLE");return {std::istreambuf_iterator<char>(f),{}};}
std::string hex(std::string_view s){std::string r;for(unsigned char c:s){r+="0123456789ABCDEF"[c>>4];r+="0123456789ABCDEF"[c&15];}return r;}
std::string unhex(std::string_view s){auto d=[](char c){if(c>='0'&&c<='9')return c-'0';if(c>='A'&&c<='F')return c-'A'+10;if(c>='a'&&c<='f')return c-'a'+10;throw std::runtime_error("FIXTURE_HEX_INVALID");};if(s.size()%2)throw std::runtime_error("FIXTURE_HEX_INVALID");std::string r;for(std::size_t i=0;i<s.size();i+=2)r+=char(d(s[i])*16+d(s[i+1]));return r;}
constexpr auto clock_value="2026-10-30 13:53:11";
constexpr auto client="11111111-1111-4111-8111-111111111111";
using Fields=std::vector<std::pair<std::string,std::string>>;
Fields common(bool ticket){Fields f={{"user-agent",docker_user_agent},{"client-id",client},{"local-time",clock_value},{"host-name","Xiaomi 6"},{"ipv4","192.0.2.2"}};if(ticket)f.emplace_back("ticket","synthetic-ticket");f.insert(f.end(),{{"ipv6",""},{"mac","02:00:00:00:00:01"},{"ostag","Xiaomi 6"}});return f;}
Fields credentials(std::string user="synthetic-user",std::string pass="synthetic-password"){return {{"user-agent",docker_user_agent},{"client-id",client},{"local-time",clock_value},{"ticket","synthetic-ticket"},{"userid",std::move(user)},{"passwd",std::move(pass)}};}
struct Case{std::string kind,input,normalized,cipher;};
std::map<std::string,Case> fixtures(){
    std::map<std::string,Case> r;
    auto request=[&](std::string name,Fields f){r.emplace(std::move(name),Case{"request",request_xml(f),{}, {}});};
    request("ticket",common(false));request("login",credentials());request("keep",common(true));request("term",common(true));
    request("unicode",credentials("\xe6\xb5\x8b\xe8\xaf\x95\xe8\xb4\xa6\xe5\x8f\xb7","\xe5\xaf\x86\xe7\xa0\x81\xf0\x9f\x94\x91"));
    request("escapes",credentials("u<&>\"'", "line1\r\nline2\t<&>\"'"));
    for(auto n:{1,7,8,9,15,16,17,31,32,33,255,256,257,4096})request("request-length-"+std::to_string(n),credentials("synthetic-user",std::string(n,'x')));
    r.emplace("markup",Case{"request","<?xml version=\"1.0\"?><request><!--synthetic--><local-time>old</local-time><probe><![CDATA[a<&>\"']]></probe><empty/></request>",{}, {}});
    auto response=[&](std::string name,std::string xml){r.emplace(std::move(name),Case{"response",std::move(xml),{}, {}});};
    response("response-ticket","<?xml version=\"1.0\" encoding=\"utf-8\"?><response><ticket>synthetic-ticket</ticket></response>");
    response("response-login","<response><keep-url>http://192.0.2.1/keep.cgi</keep-url><term-url>http://192.0.2.1/term.cgi</term-url><keep-retry>240</keep-retry></response>");
    response("response-keep","<response><interval>240</interval></response>");
    response("response-not-xml","synthetic plaintext, invalid XML");
    response("response-missing-interval","<response><error>synthetic</error></response>");
    response("response-unicode","<response><ticket>\xe6\xb5\x8b\xe8\xaf\x95&amp;synthetic</ticket></response>");
    response("response-empty","");
    for(auto n:{1,7,8,9,15,16,17,31,32,33,255,256,257,4096})response("response-length-"+std::to_string(n),std::string(n,'q'));
    return r;
}
std::map<std::string,Case> load_vectors(const char* path){
    std::ifstream f(path);if(!f)throw std::runtime_error("NATIVE_VECTORS_UNAVAILABLE");std::string line;std::getline(f,line);std::map<std::string,Case> r;
    while(std::getline(f,line)){if(!line.empty()&&line.back()=='\r')line.pop_back();std::vector<std::string> c;std::istringstream row(line);std::string v;while(std::getline(row,v,'\t'))c.push_back(v);if(c.size()!=5)throw std::runtime_error("NATIVE_VECTOR_INVALID");r.emplace(c[0],Case{c[1],unhex(c[2]),unhex(c[3]),c[4]});}return r;
}
template<class F>void rejects(F f,std::string_view expected){try{f();}catch(const std::exception& e){check(e.what()==expected,"WRONG_FAILURE_CLASSIFICATION");return;}throw std::runtime_error("REJECTION_EXPECTED");}
struct Server:Http{
    std::string module,id;const std::map<std::string,Case>& v;unsigned calls=0;std::string bad_keep;
    Server(std::string m,std::string name,const std::map<std::string,Case>& values):module(std::move(m)),id(std::move(name)),v(values){}
    HttpResponse perform(const HttpRequest& r)override{
        ++calls;check(r.body.has_value(),"SESSION_POST_REQUIRED");
        check(r.headers.at("CDC-Checksum")==checksum(*r.body),"CHECKSUM_CIPHERTEXT");check(r.headers.at("Client-ID")==client,"CLIENT_ID_FROZEN");
        check(r.headers.at("Algo-ID")== (calls==1?"00000000-0000-0000-0000-000000000000":id),"DYNAMIC_ALGORITHM_HEADER");
        if(calls==1){check(*r.body=="00000000-0000-0000-0000-000000000000","BOOTSTRAP_LITERAL");return {200,module,{}};}
        auto name=calls==2?"ticket":calls==3?"login":calls==6?"term":"keep";
        check(*r.body==v.at(name).cipher,"REQUEST_NATIVE_GOLDEN_MISMATCH");
        check(r.url==(calls==2?"http://192.0.2.1/ticket.cgi":calls==3?"http://192.0.2.1/auth.cgi":calls==6?"http://192.0.2.1/term.cgi":"http://192.0.2.1/keep.cgi"),"SESSION_ENDPOINT");
        if(calls==6){check(r.timeout_seconds==3,"TERM_BOUNDED");return {200,"",{}};}
        return {200,calls==2?v.at("response-ticket").cipher:calls==3?v.at("response-login").cipher:bad_keep.empty()?v.at("response-keep").cipher:bad_keep,{}};
    }
};
const Endpoints ep{"http://192.0.2.1/auth.cgi","http://192.0.2.1/ticket.cgi","192.0.2.2","192.0.2.3","synthetic"};
Identity identity(){return {client,"02:00:00:00:00:01",clock_value};}
}
int main(int argc,char** argv){try{
    if(argc==1){std::cout<<"Private native corpus required; skipped\n";return 77;}
    if(argc==3&&std::string_view(argv[1])=="--emit"){
        std::ofstream f(argv[2]);f<<"case\tkind\tinput_utf8_hex\n";
        for(auto&[name,c]:fixtures())f<<name<<'\t'<<c.kind<<'\t'<<hex(c.input)<<'\n';
        check(bool(f),"FIXTURE_WRITE_FAILED");return 0;
    }
    if(argc!=4){std::cerr<<"module-session-regression module native-vectors unknown-code-module\n";return 2;}
    const auto module=read(argv[1]);auto cipher=load_session_cipher(std::span(reinterpret_cast<const std::uint8_t*>(module.data()),module.size()));
    auto v=load_vectors(argv[2]);auto originals=fixtures();check(v.size()==originals.size(),"NATIVE_CORPUS_COMPLETE");
    unsigned requests=0,responses=0;
    for(const auto&[name,c]:v){check(c.input==originals.at(name).input&&c.kind==originals.at(name).kind,"SYNTHETIC_FIXTURE_IDENTITY");
        if(c.kind=="request"){
            const auto normalized=normalize_native_request_xml(c.input,clock_value);
            if(normalized!=c.normalized)throw std::runtime_error("NORMALIZATION_MISMATCH_"+name);
            check(cipher->encrypt(normalized)==c.cipher,"NATIVE_REQUEST_ENCRYPTION");++requests;
        }else{check(c.normalized==c.input,"RESPONSE_PRIMITIVE_UNALTERED");++responses;}
        check(cipher->decrypt(c.cipher)==c.normalized,"INDEPENDENT_NATIVE_DECRYPTION");
    }
    check(response_ticket(cipher->decrypt(v.at("response-unicode").cipher))=="\xe6\xb5\x8b\xe8\xaf\x95&synthetic","UNICODE_RESPONSE_PARSED");
    for(int generation=0;generation<2;++generation){Server server(module,std::string(cipher->algorithm_id()),v);Session session(server,ep,identity());unsigned ticks=0;session.on_time([&]{++ticks;return clock_value;});
        unsigned snapshots=0;session.on_bootstrap([&](auto bytes){++snapshots;check(std::string(reinterpret_cast<const char*>(bytes.data()),bytes.size())==module,"BOOTSTRAP_OBSERVER_EXACT_BYTES");});
        session.initialize();session.login("synthetic-user","synthetic-password");check(session.keep()==Seconds(240),"FIRST_KEEP_NATIVE_RESPONSE");check(session.keep()==Seconds(240),"CONTINUOUS_KEEP_NATIVE_RESPONSE");session.terminate();check(server.calls==6&&ticks==5&&snapshots==1,"NEW_SESSION_REFRESHES_MODULE_ONE_TIME_PER_REQUEST");
    }
    std::string padding=cipher->encrypt("12345678");padding[2]=padding[2]=='0'?'1':'0';
    rejects([&]{cipher->decrypt(padding);},"CIPHERTEXT_PADDING_INVALID");
    rejects([&]{cipher->decrypt("ZZ000000000000000000000000000000");},"CIPHERTEXT_HEX_INVALID");
    rejects([&]{cipher->decrypt("00");},"CIPHERTEXT_SIZE_INVALID");
    rejects([&]{cipher->encrypt(std::string(65537,'q'));},"PLAINTEXT_SIZE_INVALID");
    rejects([&]{cipher->encrypt(std::string("x\0y",3));},"PLAINTEXT_SIZE_INVALID");
    for(auto bad:{"<request><x></request>","<!DOCTYPE request><request/>","<request xmlns='urn:x'/>","<response/>"})rejects([&]{normalize_native_request_xml(bad,clock_value);},std::string_view(bad).starts_with("<request xmlns")||std::string_view(bad)=="<response/>"?"REQUEST_XML_INVALID":"XML_INVALID");
    for(const auto&[body,code]:std::vector<std::pair<std::string,std::string>>{{"ZZ000000000000000000000000000000","CIPHERTEXT_HEX_INVALID"},{padding,"CIPHERTEXT_PADDING_INVALID"},{v.at("response-not-xml").cipher,"HEARTBEAT_PROTOCOL_INVALID"},{v.at("response-missing-interval").cipher,"HEARTBEAT_PROTOCOL_INVALID"}}){
        Server server(module,std::string(cipher->algorithm_id()),v);server.bad_keep=body;Session session(server,ep,identity());session.initialize();session.login("synthetic-user","synthetic-password");rejects([&]{session.keep();},code);
    }
    for(auto bad:{read(argv[3]),module.substr(0,40),std::string(4,'\0')+char(36)+"07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA"}){
        Server server(std::move(bad),std::string(cipher->algorithm_id()),v);Session session(server,ep,identity());bool rejected=false;
        bool captured=false;session.on_bootstrap([&](auto bytes){captured=std::string(reinterpret_cast<const char*>(bytes.data()),bytes.size())==server.module;});
        try{session.initialize();}catch(const Failure& e){rejected=e.deterministic;}
        check(rejected&&server.calls==1&&captured,"BAD_OR_UNKNOWN_MODULE_NO_TICKET_POST_NO_STATIC_FALLBACK");
    }
    std::cout<<checks<<" production integration checks passed; native requests="<<requests<<" independent native responses="<<responses<<"; network=none, server_authentication=false\n";
    return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
