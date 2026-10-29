#include "esurfing/identity.hpp"
#include "esurfing/state_json.hpp"
#include "esurfing/state_store.hpp"
#include <iostream>
#include <limits>

using namespace esurfing;
namespace {
unsigned assertions=0;
void check(bool b,const char* name){++assertions;if(!b)throw std::runtime_error(name);}
template<class F> void rejects(F f,const char* name){bool failed=false;try{f();}catch(const std::exception&){failed=true;}check(failed,name);}
}
int main(){
    auto dir=std::filesystem::temp_directory_path()/("esurfing-identity-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try{
        auto noenv=[](const char*){return std::string{};};
        StateStore owner(dir);
        auto value=load_identity(dir,noenv);auto reload=load_identity(dir,noenv);
        check(value.mac==reload.mac&&value.client_id==reload.client_id,"identity stable across reads");
        check(value.mac.starts_with("02:")&&value.client_id.size()==36&&value.client_id[14]=='4'&&std::string("89ab").find(value.client_id[19])!=std::string::npos,"generated local MAC and UUID v4");
        write_state_file(dir/"device-state.json",R"({"macAddress":"AA:BB:CC:DD:EE:01","clientId":"Custom-ASCII-ID"})");
        value=load_identity(dir,noenv);
        check(value.mac=="aa:bb:cc:dd:ee:01"&&value.client_id=="custom-ascii-id","legacy JSON custom ID and lowercase preserved");
        value=load_identity(dir,[](const char* key){return std::string(key)=="DIALER_MAC_ADDRESS"?" 02:11:22:33:44:55 \n":"";});
        check(value.mac=="02:11:22:33:44:55"&&value.client_id=="custom-ascii-id","per-field env precedence");
        auto persisted=read_state_file(dir/"device-state.json");
        rejects([&]{load_identity(dir,[](const char* key){return std::string(key)=="DIALER_MAC_ADDRESS"?"not-a-mac":"";});},"invalid override rejected");
        check(read_state_file(dir/"device-state.json")==persisted,"invalid override does not overwrite good identity");
        std::filesystem::remove(dir/"device-state.json");
        write_state_file(dir/"native-mac","02:01:02:03:04:05\n");write_state_file(dir/"native-client-id","12345678-ABCD-4ABC-8ABC-123456789012\n");
        value=load_identity(dir,noenv);
        check(value.mac=="02:01:02:03:04:05"&&value.client_id=="12345678-abcd-4abc-8abc-123456789012","experimental text identity imported");
        check(std::filesystem::exists(dir/"native-mac")&&std::filesystem::exists(dir/"native-client-id"),"migration retains old text evidence");
        write_state_file(dir/"device-state.json",R"({"macAddress":"02:aa:bb:cc:dd:ee","clientId":"canonical"})");
        check(load_identity(dir,noenv).client_id=="canonical","canonical JSON outranks old experimental text");
        write_state_file(dir/"device-state.json","{broken");
        rejects([&]{load_identity(dir,noenv);},"corrupt state does not silently rotate identity");
        check(read_state_file(dir/"device-state.json")=="{broken","corrupt source retained");
        value=load_identity(dir,[](const char* key){return std::string(key)=="DIALER_MAC_ADDRESS"?"02:11:22:33:44:55":"Explicit-ID";});
        check(value.client_id=="explicit-id","both explicit overrides may repair state");
        auto unicode=parse_state_json(R"({"a":"\uD83D\uDE00","n":-9223372036854775808,"b":true,"nil":null})");
        check(std::get<std::string>(unicode.at("a"))=="\xf0\x9f\x98\x80"&&std::get<std::int64_t>(unicode.at("n"))==std::numeric_limits<std::int64_t>::min(),"strict unicode and integer boundaries");
        check(parse_state_json(encode_state_json(unicode))==unicode,"state JSON roundtrip");
        for(auto text:{"{\"a\":1,\"a\":2}","{\"a\":01}","{\"a\":1.5}","{\"a\":9223372036854775808}","{\"a\":{}}","{\"a\":[]}","{\"a\":\"\\uD800\"}","{\"a\":\"\\uDE00\"}","{} trailing","{\"a\":true,}","{\"a\":\"\xc0\x80\"}","{\"a\":\"\n\"}"})rejects([&]{parse_state_json(text);},"invalid JSON rejected");
        rejects([&]{parse_state_json(std::string(32769,' '));},"oversize JSON rejected");
        rejects([&]{read_state_file(dir/"device-state.json",2);},"bounded disk read");
        for(auto text:{R"({"macAddress":true,"clientId":"ok"})",R"({"macAddress":"02:zz:00:00:00:00","clientId":"ok"})",R"({"macAddress":"02:11:22:33:44:55","clientId":"line\nbreak"})"}){
            write_state_file(dir/"device-state.json",text);rejects([&]{load_identity(dir,noenv);},"invalid identity field rejected");
        }
        auto directory=dir/"must-remain-directory";std::filesystem::create_directory(directory);
        rejects([&]{write_state_file(directory,"{}");},"failed replace retains target");
        check(std::filesystem::is_directory(directory),"directory not replaced");
        for(const auto& entry:std::filesystem::directory_iterator(dir))check(entry.path().extension()!=".tmp","no temporary residue");
        std::cout<<assertions<<" identity/JSON assertions passed; synthetic state only\n";
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';std::filesystem::remove_all(dir);return 1;}
    std::filesystem::remove_all(dir);return 0;
}
