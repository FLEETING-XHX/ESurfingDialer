#include "esurfing/log.hpp"
#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <csignal>
#include <iostream>
#include <stdexcept>

using namespace esurfing;
int main(){
    auto dir=std::filesystem::temp_directory_path()/("native-log-pipe-"+std::to_string(getpid()));
    pid_t child=-1;int pipefd[2]{-1,-1};
    try{
        for(bool closed:{false,true}){
            if(pipe2(pipefd,O_CLOEXEC|O_NONBLOCK))throw std::runtime_error("pipe creation failed");
            if(!closed){char fill[4096]{};while(write(pipefd[1],fill,sizeof(fill))>0){}}
            else {close(pipefd[0]);pipefd[0]=-1;}
            // The runtime starts with ordinary blocking stdout/stderr descriptors.
            auto flags=fcntl(pipefd[1],F_GETFL);if(flags<0||fcntl(pipefd[1],F_SETFL,flags&~O_NONBLOCK))throw std::runtime_error("pipe flags failed");
            child=fork();if(child<0)throw std::runtime_error("fork failed");
            if(child==0){
                std::signal(SIGPIPE,SIG_IGN);
                if(dup2(pipefd[1],STDOUT_FILENO)<0||dup2(pipefd[1],STDERR_FILENO)<0)_exit(91);
                close(pipefd[1]);if(pipefd[0]>=0)close(pipefd[0]);
                bool failed=false;
                {EventLog log(LogConfig{dir/(closed?"closed":"full")});log.write(LogEvent::login_confirmed);failed=!log.finish()&&log.failed();}
                log_emergency("LOG_SINK_FAILED");
                _exit(failed?0:92);
            }
            close(pipefd[1]);pipefd[1]=-1;
            auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(6);int status=0;bool done=false;
            while(std::chrono::steady_clock::now()<deadline){if(waitpid(child,&status,WNOHANG)==child){done=true;child=-1;break;}std::this_thread::sleep_for(std::chrono::milliseconds(20));}
            if(pipefd[0]>=0)close(pipefd[0]);pipefd[0]=-1;
            if(!done||!WIFEXITED(status)||WEXITSTATUS(status)!=0)throw std::runtime_error("full/closed stdout or emergency stderr blocked, signalled, or lost sink failure");
        }
        std::filesystem::remove_all(dir);std::cout<<"two real child processes: full pipe has finite write budget; closed pipe and emergency stderr do not hang or die from SIGPIPE\n";return 0;
    }catch(const std::exception& error){
        if(child>0){kill(child,SIGKILL);waitpid(child,nullptr,0);}
        for(int fd:pipefd)if(fd>=0)close(fd);
        std::error_code ignored;std::filesystem::remove_all(dir,ignored);std::cerr<<error.what()<<'\n';return 1;
    }
}
