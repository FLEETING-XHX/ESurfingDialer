#include "esurfing/http.hpp"
#include "esurfing/module_loader.hpp"
#include "esurfing/session.hpp"
#include <curl/curl.h>
#include <fstream>
#include <sstream>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <poll.h>
#include <thread>
#include <iostream>

using namespace esurfing;
class Fixture {
    int socket_=-1;
    std::jthread thread_;
public:
    unsigned port=0;std::string request;
    explicit Fixture(std::string response,std::chrono::milliseconds delay={}) {
        socket_=socket(AF_INET,SOCK_STREAM,0);if(socket_<0)throw Failure("FIXTURE_SOCKET");
        sockaddr_in addr{};addr.sin_family=AF_INET;addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        if(bind(socket_,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))||listen(socket_,1))throw Failure("FIXTURE_BIND");
        socklen_t length=sizeof(addr);getsockname(socket_,reinterpret_cast<sockaddr*>(&addr),&length);port=ntohs(addr.sin_port);
        thread_=std::jthread([this,response,delay]{pollfd p{socket_,POLLIN,0};if(poll(&p,1,3000)<=0)return;
            int fd=accept(socket_,nullptr,nullptr);if(fd<0)return;
            timeval timeout{3,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
            char buffer[4096];auto size=recv(fd,buffer,sizeof(buffer),0);if(size>0)request.assign(buffer,static_cast<std::size_t>(size));
            if(delay.count())std::this_thread::sleep_for(delay);
            std::size_t at=0;while(at<response.size()){auto sent=send(fd,response.data()+at,response.size()-at,MSG_NOSIGNAL);if(sent<=0)break;at+=static_cast<std::size_t>(sent);}
            shutdown(fd,SHUT_RDWR);close(fd);
        });
    }
    ~Fixture(){if(thread_.joinable())thread_.join();if(socket_>=0)close(socket_);}
    std::string url()const{return "http://127.0.0.1:"+std::to_string(port)+"/test";}
    void join(){thread_.join();}
};
int main(int argc,char** argv){try{
    std::atomic_bool stop=false;CurlHttp http(stop,true);
    for(const auto* url:{"http://[::ffff:127.0.0.1]:9/","http://[0:0:0:0:0:ffff:7f00:1]:9/","http://[::ffff:169.254.1.2]/","http://127.1/","http://2130706433/","http://localhost./"}){
        bool rejected=false;try{safe_url(url);}catch(const Failure& e){rejected=std::string_view(e.what())=="URL_FORBIDDEN";}
        if(!rejected)throw Failure("LOOPBACK_LITERAL_NOT_REJECTED");
    }
    if(!forbidden_address("127.0.0.1")||!forbidden_address("::ffff:127.0.0.1")||forbidden_address("192.0.2.1")||forbidden_address("2001:db8::1"))throw Failure("SOCKET_ADDRESS_POLICY_INVALID");
    for(auto entry:{std::pair{CURLE_COULDNT_RESOLVE_HOST,"HTTP_DNS_FAILED"},std::pair{CURLE_COULDNT_CONNECT,"HTTP_CONNECT_FAILED"},std::pair{CURLE_OPERATION_TIMEDOUT,"HTTP_TIMEOUT"},std::pair{CURLE_PEER_FAILED_VERIFICATION,"HTTP_TLS_VERIFY_FAILED"},std::pair{CURLE_SSL_CONNECT_ERROR,"HTTP_TLS_HANDSHAKE_FAILED"},std::pair{CURLE_SEND_ERROR,"HTTP_SEND_FAILED"},std::pair{CURLE_RECV_ERROR,"HTTP_RECEIVE_FAILED"},std::pair{CURLE_GOT_NOTHING,"HTTP_EMPTY_REPLY"}})
        if(std::string_view(transport_failure_code(entry.first))!=entry.second)throw Failure("TRANSPORT_CLASSIFICATION_INVALID");
    {Fixture fixture("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok",std::chrono::milliseconds(1300));HttpRequest request{fixture.url()};request.timeout_seconds=1;request.diagnostic={Operation::keep,777};bool rejected=false;
        try{http.perform(request);}catch(const Failure& e){rejected=std::string_view(e.what())=="HTTP_TIMEOUT"&&e.diagnostic.operation==Operation::keep&&e.diagnostic.attempt==777&&e.diagnostic.transport_code==CURLE_OPERATION_TIMEDOUT&&e.diagnostic.elapsed_ms>=900;}
        if(!rejected)throw Failure("TIMEOUT_DIAGNOSTIC_MISSING");}
    {Fixture fixture("");HttpRequest request{fixture.url()};request.diagnostic.operation=Operation::ticket;bool rejected=false;
        try{http.perform(request);}catch(const Failure& e){rejected=std::string_view(e.what())=="HTTP_EMPTY_REPLY"&&e.diagnostic.operation==Operation::ticket&&e.diagnostic.attempt>0&&e.diagnostic.transport_code==CURLE_GOT_NOTHING;}
        if(!rejected)throw Failure("EMPTY_REPLY_DIAGNOSTIC_MISSING");}
    {struct Rejected:Http{HttpResponse perform(const HttpRequest&)override{return {503,"synthetic",{}};}} server;
        Session session(server,{"http://127.0.0.1/auth","http://127.0.0.1/ticket","192.0.2.1","192.0.2.2","synthetic"},{"synthetic","02:00:00:00:00:01","2026-11-02 00:00:00"},true);bool rejected=false;
        try{session.initialize();}catch(const Failure& e){rejected=std::string_view(e.what())=="AUTH_HTTP_ERROR"&&e.diagnostic.operation==Operation::bootstrap&&e.diagnostic.http_status==503&&e.diagnostic.attempt>0;}
        if(!rejected)throw Failure("SESSION_HTTP_STAGE_MISSING");}
    {Fixture fixture("HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");auto result=http.perform(HttpRequest{fixture.url()});if(result.status!=302||result.location!="/next")throw Failure("REDIRECT_MUST_NOT_FOLLOW");}
    {Fixture fixture("HTTP/1.1 200 OK\r\nContent-Length: 32\r\nConnection: close\r\n\r\n"+std::string(32,'x'));HttpRequest request{fixture.url()};request.limit=16;bool rejected=false;try{http.perform(request);}catch(const Failure& e){rejected=std::string(e.what())=="HTTP_BODY_TOO_LARGE";}if(!rejected)throw Failure("BODY_LIMIT_NOT_ENFORCED");}
    {Fixture fixture("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok");HttpRequest request{fixture.url()};request.body="synthetic";request.headers["CDC-Checksum"]=checksum(*request.body);auto r=http.perform(request);fixture.join();if(r.body!="ok"||fixture.request.find(docker_user_agent)==std::string::npos)throw Failure("HTTP_POST_FAILED");}
    {Fixture fixture("HTTP/1.1 302 Found\r\nLocation: /first\r\nLocation: /different\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");bool rejected=false;try{http.perform(HttpRequest{fixture.url()});}catch(const Failure& e){rejected=std::string(e.what())=="HTTP_HEADERS_INVALID";}if(!rejected)throw Failure("CONFLICTING_REDIRECT_ACCEPTED");}
    std::string binary;for(unsigned i=0;i<16384;++i)binary+=char(i%256);
    {Fixture fixture("HTTP/1.1 200 OK\r\nContent-Length: "+std::to_string(binary.size())+"\r\nConnection: close\r\n\r\n"+binary);if(http.perform(HttpRequest{fixture.url()}).body!=binary)throw Failure("BINARY_BODY_CHANGED");}
    {std::string chunks;for(std::size_t at=0;at<binary.size();at+=257){auto chunk=binary.substr(at,257);std::ostringstream size;size<<std::hex<<chunk.size();chunks+=size.str()+"\r\n"+chunk+"\r\n";}chunks+="0\r\n\r\n";Fixture fixture("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"+chunks);if(http.perform(HttpRequest{fixture.url()}).body!=binary)throw Failure("CHUNKED_BINARY_BODY_CHANGED");}
    if(argc==3){
        std::ifstream file(argv[1],std::ios::binary);if(!file)throw Failure("PRIVATE_MODULE_MISSING");
        std::string module{std::istreambuf_iterator<char>(file),{}};
        Fixture fixture("HTTP/1.1 200 OK\r\nContent-Length: "+std::to_string(module.size())+"\r\nConnection: close\r\n\r\n"+module);
        HttpRequest request{fixture.url()};request.body="00000000-0000-0000-0000-000000000000";request.limit=1024*1024;
        request.headers={{"Algo-ID",*request.body},{"Client-ID","11111111-1111-4111-8111-111111111111"},{"CDC-Checksum",checksum(*request.body)}};
        auto response=http.perform(request);if(response.body!=module)throw Failure("REAL_MODULE_HTTP_CHANGED");
        auto material=decode_module(std::span(reinterpret_cast<const std::uint8_t*>(response.body.data()),response.body.size()),read_known_native_library(argv[2]));
        if(material.algorithm_id.empty())throw Failure("REAL_MODULE_DECODE_FAILED");
        std::cout<<"Private field module passed real loopback HTTP and production decoding\n";
    }
    stop=true;bool cancelled=false;try{http.perform(HttpRequest{"http://127.0.0.1/"});}catch(const Failure& e){cancelled=std::string(e.what())=="CANCELLED";}if(!cancelled)throw Failure("CANCELLATION_FAILED");
    std::cout<<"HTTP loopback and timeout/empty-reply/status-stage diagnostics passed; no external network\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
