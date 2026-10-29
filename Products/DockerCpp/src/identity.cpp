#include "esurfing/identity.hpp"
#include "esurfing/state_json.hpp"
#include <random>
#include <stdexcept>

namespace esurfing {
namespace {
std::string trim(std::string s){auto first=s.find_first_not_of(" \t\r\n");return first==s.npos?"":s.substr(first,s.find_last_not_of(" \t\r\n")-first+1);}
std::string lower(std::string s){for(auto& c:s)if(c>='A'&&c<='Z')c=static_cast<char>(c-'A'+'a');return s;}
bool valid_mac(const std::string& s){if(s.size()!=17)return false;for(unsigned i=0;i<17;++i){if(i%3==2){if(s[i]!=':')return false;}else if(std::string_view("0123456789abcdef").find(s[i])==std::string_view::npos)return false;}return true;}
// Retain legacy custom ASCII IDs, rather than silently replacing them with UUIDs.
bool valid_id(const std::string& s){if(s.empty()||s.size()>256||s.find_first_not_of(' ')==s.npos)return false;for(unsigned char c:s)if(c<32||c>126)return false;return true;}
std::string field(const JsonObject& object,const char* key){auto it=object.find(key);if(it==object.end())return {};auto p=std::get_if<std::string>(&it->second);if(!p)throw std::runtime_error("DEVICE_IDENTITY_INVALID");return *p;}
}
DeviceIdentity load_identity(const std::filesystem::path& dir,const std::function<std::string(const char*)>& env){
    auto mac=lower(trim(env("DIALER_MAC_ADDRESS"))),id=lower(trim(env("DIALER_CLIENT_ID")));
    auto path=dir/"device-state.json";
    JsonObject object;
    if(std::filesystem::exists(std::filesystem::symlink_status(path))){
        // Explicitly setting both fields can repair an invalid old file. Never reset
        // a partially available identity just because parsing failed.
        if(mac.empty()||id.empty())object=parse_state_json(read_state_file(path));
    }
    if(mac.empty())mac=lower(field(object,"macAddress"));
    if(id.empty())id=lower(field(object,"clientId"));
    if(mac.empty()&&std::filesystem::exists(std::filesystem::symlink_status(dir/"native-mac")))mac=lower(trim(read_state_file(dir/"native-mac",128)));
    if(id.empty()&&std::filesystem::exists(std::filesystem::symlink_status(dir/"native-client-id")))id=lower(trim(read_state_file(dir/"native-client-id",512)));
    std::random_device random;const char* hex="0123456789abcdef";
    if(mac.empty()){mac="02";for(int i=0;i<5;++i){auto b=random()&255;mac+=':';mac+=hex[b>>4];mac+=hex[b&15];}}
    if(id.empty()){for(unsigned i=0;i<36;++i){if(i==8||i==13||i==18||i==23)id+='-';else id+=hex[random()&15];}id[14]='4';id[19]=hex[8+(random()&3)];}
    if(!valid_mac(mac)||!valid_id(id))throw std::runtime_error("DEVICE_IDENTITY_INVALID");
    write_state_file(path,encode_state_json({{"macAddress",mac},{"clientId",id}}));
    return {id,mac};
}
}
