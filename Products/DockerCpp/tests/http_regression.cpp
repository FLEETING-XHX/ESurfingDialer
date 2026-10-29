#include "esurfing/http.hpp"
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
    explicit Fixture(std::string response) {
        socket_=socket(AF_INET,SOCK_STREAM,0);if(socket_<0)throw Failure("FIXTURE_SOCKET");
        sockaddr_in addr{};addr.sin_family=AF_INET;addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        if(bind(socket_,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))||listen(socket_,1))throw Failure("FIXTURE_BIND");
        socklen_t length=sizeof(addr);getsockname(socket_,reinterpret_cast<sockaddr*>(&addr),&length);port=ntohs(addr.sin_port);
        thread_=std::jthread([this,response]{pollfd p{socket_,POLLIN,0};if(poll(&p,1,3000)<=0)return;
            int fd=accept(socket_,nullptr,nullptr);if(fd<0)return;
            timeval timeout{3,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
            char buffer[4096];auto size=recv(fd,buffer,sizeof(buffer),0);if(size>0)request.assign(buffer,static_cast<std::size_t>(size));
            std::size_t at=0;while(at<response.size()){auto sent=send(fd,response.data()+at,response.size()-at,MSG_NOSIGNAL);if(sent<=0)break;at+=static_cast<std::size_t>(sent);}
            shutdown(fd,SHUT_RDWR);close(fd);
        });
    }
    ~Fixture(){if(thread_.joinable())thread_.join();if(socket_>=0)close(socket_);}
    std::string url()const{return "http://127.0.0.1:"+std::to_string(port)+"/test";}
    void join(){thread_.join();}
};
int main(){try{
    std::atomic_bool stop=false;CurlHttp http(stop,true);
    {Fixture fixture("HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");auto result=http.perform(HttpRequest{fixture.url()});if(result.status!=302||result.location!="/next")throw Failure("REDIRECT_MUST_NOT_FOLLOW");}
    {Fixture fixture("HTTP/1.1 200 OK\r\nContent-Length: 32\r\nConnection: close\r\n\r\n"+std::string(32,'x'));HttpRequest request{fixture.url()};request.limit=16;bool rejected=false;try{http.perform(request);}catch(const Failure& e){rejected=std::string(e.what())=="HTTP_BODY_TOO_LARGE";}if(!rejected)throw Failure("BODY_LIMIT_NOT_ENFORCED");}
    {Fixture fixture("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok");HttpRequest request{fixture.url()};request.body="synthetic";request.headers["CDC-Checksum"]=checksum(*request.body);auto r=http.perform(request);fixture.join();if(r.body!="ok"||fixture.request.find(docker_user_agent)==std::string::npos)throw Failure("HTTP_POST_FAILED");}
    {Fixture fixture("HTTP/1.1 302 Found\r\nLocation: /first\r\nLocation: /different\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");bool rejected=false;try{http.perform(HttpRequest{fixture.url()});}catch(const Failure& e){rejected=std::string(e.what())=="HTTP_HEADERS_INVALID";}if(!rejected)throw Failure("CONFLICTING_REDIRECT_ACCEPTED");}
    stop=true;bool cancelled=false;try{http.perform(HttpRequest{"http://127.0.0.1/"});}catch(const Failure& e){cancelled=std::string(e.what())=="CANCELLED";}if(!cancelled)throw Failure("CANCELLATION_FAILED");
    std::cout<<"5 real loopback HTTP scenarios passed; no external network\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
