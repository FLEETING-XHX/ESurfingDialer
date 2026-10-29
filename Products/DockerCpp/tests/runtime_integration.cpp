#include "esurfing/cipher.hpp"
#include "esurfing/protocol.hpp"
#include "esurfing/state_json.hpp"
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <atomic>
#include <csignal>
#include <fstream>
#include <iostream>
#include <mutex>
#include <thread>

using namespace esurfing;
namespace {
constexpr const char* algorithm="07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA";
constexpr const char* secret="synthetic-password-MUST-NOT-LOG";
constexpr const char* user="synthetic-account-MUST-NOT-LOG";
void require(bool b,const char* code){if(!b)throw Failure(code);}
class Server {
    int listener_=-1;
    Cipher cipher_{algorithm};
    std::jthread acceptor_;
    std::vector<std::jthread> handlers_;
    std::atomic_bool stopping_=false;
    unsigned probes_=0;
    bool reject_modules_=false;
    std::string portal()const{return "<!--//config.campus.js.chinatelecom.com<settings><auth-url>"+url()+"/auth.cgi</auth-url><ticket-url>"+url()+"/ticket.cgi?wlanuserip=10.0.0.2&amp;wlanacip=10.0.0.3</ticket-url></settings>//config.campus.js.chinatelecom.com-->";}
    std::string response(const std::string& path,const std::string& body,const std::string& headers){
        if(path=="/probe"){
            if(reject_modules_&&bootstraps>=3)return "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            if(!reject_modules_&&++probes_>1){slow_probe=true;while(!stopping_)std::this_thread::sleep_for(std::chrono::milliseconds(5));}
            return "HTTP/1.1 302 Found\r\nLocation: "+url()+"/portal?wlanuserip=10.0.0.2&wlanacip=10.0.0.3\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        }
        std::string data;
        if(path.starts_with("/portal"))data=portal();
        else {
            require(headers.find("Client-ID: synthetic-legacy-id")!=headers.npos,"RUNTIME_IDENTITY_CHANGED");
            require(headers.find("CDC-Checksum: "+checksum(body))!=headers.npos,"RUNTIME_CHECKSUM_INVALID");
            if(body=="00000000-0000-0000-0000-000000000000"){
                ++bootstraps;
                data=std::string(4,'\0')+char(36)+(reject_modules_?"FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF":algorithm);
            }else{
                auto xml=cipher_.decrypt(body);
                require(xml.find(docker_user_agent)!=xml.npos,"RUNTIME_CHANNEL_CHANGED");
                require(xml.find("synthetic-legacy-id")!=xml.npos,"RUNTIME_REQUEST_ID_CHANGED");
                if(path.starts_with("/ticket.cgi"))data=cipher_.encrypt("<response><ticket>synthetic-ticket-MUST-NOT-LOG</ticket></response>");
                else if(path=="/auth.cgi"){
                    require(xml.find(user)!=xml.npos&&xml.find(std::string("<passwd>")+secret+"</passwd>")!=xml.npos,"RUNTIME_CREDENTIAL_NOT_SENT");
                    data=cipher_.encrypt("<response><keep-url>"+url()+"/keep.cgi</keep-url><term-url>"+url()+"/term.cgi</term-url><keep-retry>5</keep-retry></response>");
                }else{
                    require(xml.find("10.0.0.2")!=xml.npos&&xml.find("02:11:22:33:44:55")!=xml.npos,"RUNTIME_FROZEN_CONTEXT_CHANGED");
                    if(path=="/keep.cgi"){++keeps;data=cipher_.encrypt(reject_keeps?"<response><interval>invalid</interval></response>":"<response><interval>5</interval></response>");}
                    else if(path=="/term.cgi"){++terms;data="accepted fixture transport";}
                    else throw Failure("RUNTIME_UNKNOWN_PATH");
                }
            }
        }
        return "HTTP/1.1 200 OK\r\nContent-Length: "+std::to_string(data.size())+"\r\nConnection: close\r\n\r\n"+data;
    }
    void handle(int fd){
        try{
            timeval timeout{3,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
            std::string request;char buffer[4096];std::size_t split;
            while((split=request.find("\r\n\r\n"))==request.npos){auto n=recv(fd,buffer,sizeof(buffer),0);require(n>0,"FIXTURE_REQUEST_TRUNCATED");request.append(buffer,static_cast<std::size_t>(n));require(request.size()<16384,"FIXTURE_HEADERS_LARGE");}
            auto headers=request.substr(0,split);std::size_t length=0;auto size_at=headers.find("Content-Length: ");
            if(size_at!=headers.npos)length=std::stoul(headers.substr(size_at+16));require(length<128*1024,"FIXTURE_BODY_LARGE");
            auto body=request.substr(split+4);while(body.size()<length){auto n=recv(fd,buffer,sizeof(buffer),0);require(n>0,"FIXTURE_BODY_TRUNCATED");body.append(buffer,static_cast<std::size_t>(n));}
            auto begin=request.find(' '),end=request.find(' ',begin+1);require(begin!=request.npos&&end!=request.npos,"FIXTURE_REQUEST_INVALID");
            auto reply=response(request.substr(begin+1,end-begin-1),body,headers);std::size_t at=0;
            while(at<reply.size()){auto n=send(fd,reply.data()+at,reply.size()-at,MSG_NOSIGNAL);if(n<=0)break;at+=static_cast<std::size_t>(n);}
        }catch(const std::exception&){failed=true;}
        shutdown(fd,SHUT_RDWR);close(fd);
    }
public:
    unsigned port=0;
    std::atomic_bool failed=false,slow_probe=false,reject_keeps=false;
    std::atomic_uint keeps=0,terms=0,bootstraps=0;
    explicit Server(bool reject_modules=false):reject_modules_(reject_modules){
        listener_=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);require(listener_>=0,"FIXTURE_SOCKET_FAILED");
        sockaddr_in addr{};addr.sin_family=AF_INET;addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        require(bind(listener_,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))==0&&listen(listener_,16)==0,"FIXTURE_BIND_FAILED");
        socklen_t length=sizeof(addr);require(getsockname(listener_,reinterpret_cast<sockaddr*>(&addr),&length)==0,"FIXTURE_ADDRESS_FAILED");port=ntohs(addr.sin_port);
    }
    std::string url()const{return "http://127.0.0.1:"+std::to_string(port);}
    void start(){acceptor_=std::jthread([this]{while(!stopping_){pollfd p{listener_,POLLIN,0};if(poll(&p,1,100)<=0)continue;int fd=accept4(listener_,nullptr,nullptr,SOCK_CLOEXEC);if(fd>=0)handlers_.emplace_back([this,fd]{handle(fd);});}});}
    ~Server(){stopping_=true;if(acceptor_.joinable())acceptor_.join();for(auto& h:handlers_)if(h.joinable())h.join();if(listener_>=0)close(listener_);}
};
}
int main(int argc,char** argv){
    pid_t child=-1;auto dir=std::filesystem::temp_directory_path()/("esurfing-runtime-"+std::to_string(getpid()));
    try{
        const bool paused_mode=argc==3&&std::string(argv[2])=="--paused";
        const bool log_failure=argc==3&&std::string(argv[2])=="--log-failure";
        require(argc==2||paused_mode||log_failure,"FIXTURE_BINARY_REQUIRED");std::filesystem::create_directory(dir);
        write_state_file(dir/"device-state.json",R"({"macAddress":"02:11:22:33:44:55","clientId":"Synthetic-Legacy-ID"})");
        auto secret_file=(dir/"synthetic.secret").string();write_state_file(secret_file,std::string(secret)+"\r\n");
        Server server(paused_mode);auto probe_url=server.url()+"/probe";auto state_dir=dir.string();auto log=(dir/"runtime.log").string();
        child=fork();require(child>=0,"FIXTURE_FORK_FAILED");
        if(child==0){
            int out=open(log.c_str(),O_CREAT|O_WRONLY|O_TRUNC,0600);if(out<0)_exit(90);dup2(out,STDOUT_FILENO);dup2(out,STDERR_FILENO);close(out);
            setenv("NATIVE_EXPERIMENTAL","1",1);setenv("AUTO_REAUTH_ENABLED","1",1);setenv("STATE_DIR",state_dir.c_str(),1);
            setenv("NETWORK_CHECK_URLS",probe_url.c_str(),1);setenv("NETWORK_CHECK_INTERVAL_SECONDS","1",1);
            setenv("HEALTH_WRITE_INTERVAL_SECONDS","1",1);setenv("LOGIN_CONFIRMATION_INTERVAL_SECONDS","1",1);
            setenv("LOGIN_RETRY_INITIAL_SECONDS","1",1);setenv("LOGIN_RETRY_MAX_SECONDS","5",1);
            setenv("DIALER_USER",user,1);setenv("DIALER_PASSWORD","wrong-environment-value",1);setenv("DIALER_PASSWORD_FILE",secret_file.c_str(),1);unsetenv("DIALER_CLIENT_ID");unsetenv("DIALER_MAC_ADDRESS");
            unsetenv("LOG_DIR");unsetenv("LOG_FILE_MAX_BYTES");unsetenv("LOG_TOTAL_MAX_BYTES");unsetenv("LOG_RETENTION_DAYS");unsetenv("LOG_ROTATED_FILES");
            execl(argv[1],argv[1],static_cast<char*>(nullptr));_exit(91);
        }
        server.start();auto deadline=std::chrono::steady_clock::now()+Seconds(18);JsonObject health;
        while(std::chrono::steady_clock::now()<deadline){
            try{health=parse_state_json(read_state_file(dir/"health.json"));}catch(const std::exception&){}
            if(paused_mode){
                if(health.contains("phase")&&std::get<std::string>(health.at("phase"))=="paused"&&std::get<bool>(health.at("networkOnline"))&&!std::get<bool>(health.at("authenticated")))break;
            }else if(server.keeps>=2&&server.slow_probe&&health.contains("authenticated")&&std::get<bool>(health.at("authenticated")))break;
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        if(paused_mode){
            require(!server.failed&&server.bootstraps==3&&health.contains("phase")&&std::get<std::string>(health.at("phase"))=="paused"&&std::get<bool>(health.at("networkOnline"))&&!std::get<bool>(health.at("authenticated")),"PAUSED_NETWORK_SNAPSHOT_STALE_OR_UNBOUNDED_RETRY");
            require(std::get<std::int64_t>(health.at("autoReauthLastAuthSuccessAt"))==0,"UNSUPPORTED_ALGORITHM_CANNOT_RECORD_AUTH_SUCCESS");
        }else{
            require(!server.failed&&server.keeps>=2&&server.slow_probe,"SLOW_PROBE_PREVENTED_RUNTIME_KEEP");
            require(std::get<bool>(health.at("authenticated"))&&std::get<bool>(health.at("autoReauthEnabled")),"RUNTIME_NOT_AUTHENTICATED");
            require(std::get<std::int64_t>(health.at("autoReauthLastAuthSuccessAt"))>0&&std::get<std::int64_t>(health.at("autoReauthNextPlannedAt"))>0,"RUNTIME_PLAN_NOT_UPDATED");
        }
        require(std::get<std::int64_t>(health.at("processId"))==child&&!std::get<std::string>(health.at("instanceId")).empty(),"RUNTIME_OWNER_IDENTITY_INVALID");
        if(log_failure){
            // Turn the active file into a directory. The next heartbeat error must cause
            // a fatal log write failure, then cancel the real process without an orphan.
            bool found=false;
            for(const auto& entry:std::filesystem::directory_iterator(dir/"logs"))if(entry.path().extension()==".log"){
                std::filesystem::rename(entry.path(),entry.path().string()+".saved");std::filesystem::create_directory(entry.path());found=true;
            }
            require(found,"RUNTIME_FILE_LOG_MISSING");server.reject_keeps=true;
        }
        auto stopping=std::chrono::steady_clock::now();if(!log_failure)kill(child,SIGTERM);int status=0;bool exited=false;
        while(std::chrono::steady_clock::now()-stopping<Seconds(log_failure?12:6)){if(waitpid(child,&status,WNOHANG)==child){exited=true;child=-1;break;}std::this_thread::sleep_for(std::chrono::milliseconds(50));}
        require(exited&&WIFEXITED(status)&&WEXITSTATUS(status)==(log_failure?1:0),"RUNTIME_STOP_FAILED");
        health=parse_state_json(read_state_file(dir/"health.json"));
        require(!std::get<bool>(health.at("processAlive"))&&!std::get<bool>(health.at("authenticated"))&&std::get<std::string>(health.at("phase"))=="stopped","RUNTIME_FINAL_HEALTH_INVALID");
        require(server.terms==(paused_mode?0u:1u),"RUNTIME_DYNAMIC_TERM_NOT_CALLED");auto logs=read_state_file(dir/"runtime.log");
        std::string file_logs;
        for(const auto& entry:std::filesystem::directory_iterator(dir/"logs"))if(entry.is_regular_file()&&entry.path().filename()!="native-logs.lock")file_logs+=read_state_file(entry.path());
        require(file_logs.find("STATE")!=file_logs.npos,"RUNTIME_EVENTS_NOT_ARCHIVED");
        if(!paused_mode)require(file_logs.find("HEARTBEAT_SUMMARY successes=1")!=file_logs.npos,"RUNTIME_HEARTBEAT_SUMMARY_MISSING");
        for(auto sensitive:{secret,user,"synthetic-ticket-MUST-NOT-LOG","127.0.0.1","02:11:22:33:44:55"})require(logs.find(sensitive)==logs.npos&&file_logs.find(sensitive)==file_logs.npos,"RUNTIME_SECRET_LOGGED");
        if(log_failure)std::cout<<"real process file-log failure cancels network/session, exits 1, writes stopped health and attempts term once; synthetic loopback only\n";
        else if(paused_mode)std::cout<<"real process unknown algorithm stops after three attempts; paused owner still observes external online without claiming authentication; SIGTERM passed\n";
        else std::cout<<"real process + loopback synthetic bootstrap/ticket/login/first keep/continuing keep/term, slow-probe independence, planner/identity and SIGTERM passed; NOT native/server equivalence\n";
    }catch(const std::exception& e){
        if(child>0){kill(child,SIGKILL);waitpid(child,nullptr,0);}std::cerr<<e.what()<<'\n';
        if(std::filesystem::exists(dir/"runtime.log"))std::cerr<<read_state_file(dir/"runtime.log");
        std::filesystem::remove_all(dir);return 1;
    }
    std::filesystem::remove_all(dir);return 0;
}
