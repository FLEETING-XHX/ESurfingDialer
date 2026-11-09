// Synthetic, offline runtime compatibility checks. Never copied into /app.
#include <curl/curl.h>
#include "esurfing/http.hpp"
#include <openssl/pem.h>
#include <openssl/ssl.h>
#include <openssl/x509v3.h>
#include <libxml/parser.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <iconv.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <array>
#include <algorithm>
#include <csignal>
#include <cstdlib>
#include <ctime>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>

namespace {
void require(bool ok,const char* code){if(!ok)throw std::runtime_error(code);}
size_t discard(char*,size_t size,size_t count,void*){return size*count;}
void character_conversion(const char* encoding,std::string encoded,const std::string& expected){
    auto converter=iconv_open("UTF-8",encoding);require(converter!=iconv_t(-1),"CHARSET_MODULES");
    char decoded[128]{};char* input=encoded.data();char* output=decoded;
    size_t input_size=encoded.size(),output_size=sizeof(decoded);
    auto result=iconv(converter,&input,&input_size,&output,&output_size);iconv_close(converter);
    require(result!=size_t(-1)&&input_size==0&&std::string(decoded,output-decoded)==expected,"CHARSET_CONVERSION");
}
class TlsFixture {
    EVP_PKEY* key_=nullptr;X509* certificate_=nullptr;SSL_CTX* context_=nullptr;
    int listener_=-1;std::jthread thread_;
public:
    std::string ca;unsigned port=0;
    TlsFixture(){
        key_=EVP_PKEY_Q_keygen(nullptr,nullptr,"RSA",2048);require(key_,"TLS_KEYGEN");
        certificate_=X509_new();require(certificate_,"TLS_CERTIFICATE");
        X509_set_version(certificate_,2);ASN1_INTEGER_set(X509_get_serialNumber(certificate_),1);
        X509_gmtime_adj(X509_getm_notBefore(certificate_),-60);X509_gmtime_adj(X509_getm_notAfter(certificate_),3600);
        X509_set_pubkey(certificate_,key_);auto name=X509_get_subject_name(certificate_);
        X509_NAME_add_entry_by_txt(name,"CN",MBSTRING_ASC,reinterpret_cast<const unsigned char*>("localhost"),-1,-1,0);
        X509_set_issuer_name(certificate_,name);
        auto san=X509V3_EXT_conf_nid(nullptr,nullptr,NID_subject_alt_name,const_cast<char*>("DNS:localhost"));
        require(san,"TLS_SAN");X509_add_ext(certificate_,san,-1);X509_EXTENSION_free(san);
        require(X509_sign(certificate_,key_,EVP_sha256())>0,"TLS_SIGN");
        std::array<char,64> path{};std::string pattern="/tmp/esurfing-synthetic-ca-XXXXXX";
        std::copy(pattern.begin(),pattern.end(),path.begin());int file=mkstemp(path.data());require(file>=0,"TLS_TEMPFILE");
        ca=path.data();auto stream=fdopen(file,"w");require(stream,"TLS_TEMP_STREAM");
        require(PEM_write_X509(stream,certificate_)==1,"TLS_PEM");fclose(stream);
        context_=SSL_CTX_new(TLS_server_method());require(context_,"TLS_CONTEXT");
        require(SSL_CTX_use_certificate(context_,certificate_)==1&&SSL_CTX_use_PrivateKey(context_,key_)==1,"TLS_CONTEXT_KEY");
        listener_=socket(AF_INET,SOCK_STREAM,0);require(listener_>=0,"TLS_SOCKET");
        sockaddr_in address{};address.sin_family=AF_INET;address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        require(bind(listener_,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0&&listen(listener_,8)==0,"TLS_BIND");
        socklen_t length=sizeof(address);getsockname(listener_,reinterpret_cast<sockaddr*>(&address),&length);port=ntohs(address.sin_port);
        thread_=std::jthread([this](std::stop_token stop){
            while(!stop.stop_requested()){
                pollfd p{listener_,POLLIN,0};if(poll(&p,1,100)<=0)continue;
                int socket=accept(listener_,nullptr,nullptr);if(socket<0)continue;
                timeval timeout{2,0};setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));setsockopt(socket,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
                auto ssl=SSL_new(context_);if(ssl){SSL_set_fd(ssl,socket);
                    if(SSL_accept(ssl)==1){char request[4096];if(SSL_read(ssl,request,sizeof(request))>0){
                        constexpr auto response="HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok";
                        SSL_write(ssl,response,int(std::char_traits<char>::length(response)));SSL_shutdown(ssl);
                    }}SSL_free(ssl);
                }close(socket);
            }
        });
    }
    ~TlsFixture(){thread_.request_stop();if(thread_.joinable())thread_.join();if(listener_>=0)close(listener_);if(context_)SSL_CTX_free(context_);if(certificate_)X509_free(certificate_);if(key_)EVP_PKEY_free(key_);if(!ca.empty())unlink(ca.c_str());}
    CURLcode request(const char* host,bool trust){
        auto curl=curl_easy_init();require(curl,"CURL_INIT");
        auto url="https://"+std::string(host)+":"+std::to_string(port)+"/synthetic";
        curl_easy_setopt(curl,CURLOPT_URL,url.c_str());curl_easy_setopt(curl,CURLOPT_PROXY,"");
        curl_easy_setopt(curl,CURLOPT_TIMEOUT,4L);curl_easy_setopt(curl,CURLOPT_NOSIGNAL,1L);
        curl_easy_setopt(curl,CURLOPT_SSL_VERIFYPEER,1L);curl_easy_setopt(curl,CURLOPT_SSL_VERIFYHOST,2L);
        curl_easy_setopt(curl,CURLOPT_WRITEFUNCTION,discard);
        if(trust)curl_easy_setopt(curl,CURLOPT_CAINFO,ca.c_str());
        auto result=curl_easy_perform(curl);long status=0;curl_easy_getinfo(curl,CURLINFO_RESPONSE_CODE,&status);
        curl_easy_cleanup(curl);if(result==CURLE_OK)require(status==200,"TLS_HTTP_STATUS");return result;
    }
};
}
int main(){try{
    std::signal(SIGPIPE,SIG_IGN);require(curl_global_init(CURL_GLOBAL_DEFAULT)==CURLE_OK,"CURL_GLOBAL");
    for(auto name:std::array<const char*,2>{"localhost",std::getenv("SLIM_TEST_DNS_NAME")})if(name){
        addrinfo* addresses=nullptr;require(getaddrinfo(name,nullptr,nullptr,&addresses)==0&&addresses,"NAME_RESOLUTION");freeaddrinfo(addresses);
    }
    std::time_t epoch=0;
    const std::string chinese="\xe4\xb8\xad\xe6\x96\x87";
    for(auto encoding:{"GB18030","GBK","CP936","GB2312"})character_conversion(encoding,"\xd6\xd0\xce\xc4",chinese);
    for(auto encoding:{"BIG5","BIG5-HKSCS"})character_conversion(encoding,"\xa4\xa4\xa4\xe5",chinese);
    character_conversion("UTF-8",chinese,chinese);
    character_conversion("UTF-16LE",std::string("\x2d\x4e\x87\x65",4),chinese);
    character_conversion("UTF-32BE",std::string("\0\0\x4e\x2d\0\0\x65\x87",8),chinese);
    character_conversion("ISO-8859-1","\xe9","\xc3\xa9");
    character_conversion("ISO-8859-15","\xa4","\xe2\x82\xac");
    character_conversion("WINDOWS-1252","\x80","\xe2\x82\xac");
    std::string legacy_xml="<?xml version=\"1.0\" encoding=\"GB18030\"?><response><message>\xd6\xd0\xce\xc4</message></response>";
    auto document=xmlReadMemory(legacy_xml.data(),int(legacy_xml.size()),nullptr,nullptr,XML_PARSE_NONET);
    require(document,"XML_LEGACY_ENCODING");auto content=xmlNodeGetContent(xmlDocGetRootElement(document));
    auto decoded_xml=content?std::string(reinterpret_cast<const char*>(content)):std::string{};
    xmlFree(content);xmlFreeDoc(document);require(decoded_xml==chinese,"XML_LEGACY_CONVERSION");
    if(std::getenv("SLIM_TEST_LEAN_PROFILE")){
        require(!xmlHasFeature(XML_WITH_ICU)&&xmlHasFeature(XML_WITH_ICONV),"XML_LEAN_FEATURES");
        auto version=curl_version_info(CURLVERSION_NOW);require(version&&version->protocols,"CURL_FEATURES");
        unsigned protocols=0;for(auto protocol=version->protocols;*protocol;++protocol){
            require(std::string(*protocol)=="http"||std::string(*protocol)=="https","CURL_EXTRA_PROTOCOL");++protocols;
        }
        require(protocols==2&&(version->features&CURL_VERSION_SSL)&&(version->features&CURL_VERSION_ASYNCHDNS),"CURL_LEAN_FEATURES");
    }
    for(auto entry:{std::pair{"UTC",0},std::pair{"Asia/Shanghai",8},std::pair{"America/New_York",19}}){
        setenv("TZ",entry.first,1);tzset();std::tm result{};require(localtime_r(&epoch,&result)&&result.tm_hour==entry.second,"TIMEZONE_DATA");
    }
    auto trust=SSL_CTX_new(TLS_client_method());require(trust,"TRUST_CONTEXT");
    require(SSL_CTX_load_verify_locations(trust,"/etc/ssl/certs/ca-certificates.crt",nullptr)==1,"SYSTEM_CA_BUNDLE");SSL_CTX_free(trust);
    {TlsFixture fixture;
        require(fixture.request("localhost",true)==CURLE_OK,"TRUSTED_TLS_FAILED");
        require(fixture.request("localhost",false)==CURLE_PEER_FAILED_VERIFICATION,"UNTRUSTED_TLS_ACCEPTED");
        require(fixture.request("127.0.0.1",true)==CURLE_PEER_FAILED_VERIFICATION,"WRONG_HOST_ACCEPTED");
        std::atomic_bool stop=false;esurfing::CurlHttp transport(stop,true);
        esurfing::HttpRequest request{"https://localhost:"+std::to_string(fixture.port)+"/synthetic"};request.diagnostic={esurfing::Operation::keep,778};bool rejected=false;
        try{transport.perform(request);}catch(const esurfing::Failure& error){rejected=std::string_view(error.what())=="HTTP_TLS_VERIFY_FAILED"&&error.diagnostic.transport_code==CURLE_PEER_FAILED_VERIFICATION&&error.diagnostic.attempt==778&&error.diagnostic.operation==esurfing::Operation::keep;}
        require(rejected,"PRODUCTION_TLS_DIAGNOSTIC_MISSING");
    }
    curl_global_cleanup();std::cout<<"Runtime DNS, 12 encoding conversions, GB18030 XML, 3 timezones, CA trust, trusted TLS and certificate/hostname rejection passed; synthetic only\n";return 0;
}catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}}
