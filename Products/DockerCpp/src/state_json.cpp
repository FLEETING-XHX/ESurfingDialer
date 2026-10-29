#include "esurfing/state_json.hpp"
#include "esurfing/core.hpp"
#include <charconv>
#include <fstream>
#include <random>
#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#endif

namespace esurfing {
namespace {
[[noreturn]] void invalid() { throw std::runtime_error("STATE_JSON_INVALID"); }
void utf8(std::string_view s) {
    for(std::size_t i=0;i<s.size();) {
        auto c=static_cast<unsigned char>(s[i++]);
        if(c<128)continue;
        unsigned n=0,code=0,min=0;
        if(c>=0xc2&&c<=0xdf){n=1;code=c&31;min=128;}
        else if(c>=0xe0&&c<=0xef){n=2;code=c&15;min=2048;}
        else if(c>=0xf0&&c<=0xf4){n=3;code=c&7;min=65536;}
        else invalid();
        while(n--){if(i==s.size())invalid();auto b=static_cast<unsigned char>(s[i++]);if((b&0xc0)!=0x80)invalid();code=(code<<6)|(b&63);}
        if(code<min||code>0x10ffff||(code>=0xd800&&code<=0xdfff))invalid();
    }
}
class Parser {
    std::string_view s;std::size_t at=0;
    void ws(){while(at<s.size()&&(s[at]==' '||s[at]=='\n'||s[at]=='\r'||s[at]=='\t'))++at;}
    bool take(char c){ws();if(at<s.size()&&s[at]==c){++at;return true;}return false;}
    unsigned hex4(){unsigned n=0;for(int i=0;i<4;++i){if(at==s.size())invalid();auto c=s[at++];int d=c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;if(d<0)invalid();n=n*16+static_cast<unsigned>(d);}return n;}
    void append(std::string& out,unsigned c){
        if(c<128)out+=static_cast<char>(c);
        else if(c<2048){out+=static_cast<char>(0xc0|(c>>6));out+=static_cast<char>(0x80|(c&63));}
        else if(c<65536){out+=static_cast<char>(0xe0|(c>>12));out+=static_cast<char>(0x80|((c>>6)&63));out+=static_cast<char>(0x80|(c&63));}
        else{out+=static_cast<char>(0xf0|(c>>18));out+=static_cast<char>(0x80|((c>>12)&63));out+=static_cast<char>(0x80|((c>>6)&63));out+=static_cast<char>(0x80|(c&63));}
    }
    std::string string(){
        if(!take('"'))invalid();
        std::string out;
        while(at<s.size()){
            auto c=static_cast<unsigned char>(s[at++]);
            if(c=='"')return out;
            if(c<32)invalid();
            if(c!='\\'){out+=static_cast<char>(c);continue;}
            if(at==s.size())invalid();
            char escape=s[at++];
            switch(escape){
            case '"':case '\\':case '/':out+=escape;break;
            case 'b':out+='\b';break;case 'f':out+='\f';break;case 'n':out+='\n';break;case 'r':out+='\r';break;case 't':out+='\t';break;
            case 'u':{auto code=hex4();if(code>=0xd800&&code<=0xdbff){if(at+2>s.size()||s.substr(at,2)!="\\u")invalid();at+=2;auto low=hex4();if(low<0xdc00||low>0xdfff)invalid();code=0x10000+((code-0xd800)<<10)+(low-0xdc00);}else if(code>=0xdc00&&code<=0xdfff)invalid();append(out,code);break;}
            default:invalid();
            }
        }invalid();
    }
    JsonValue value(){
        ws();if(at==s.size())invalid();if(s[at]=='"')return string();
        if(s.substr(at,4)=="null"){at+=4;return nullptr;}
        if(s.substr(at,4)=="true"){at+=4;return true;}
        if(s.substr(at,5)=="false"){at+=5;return false;}
        auto start=at;if(s[at]=='-')++at;if(at==s.size()||s[at]<'0'||s[at]>'9')invalid();
        if(s[at]=='0')++at;else while(at<s.size()&&s[at]>='0'&&s[at]<='9')++at;
        std::int64_t n=0;auto [p,e]=std::from_chars(s.data()+start,s.data()+at,n);if(e!=std::errc{}||p!=s.data()+at)invalid();return n;
    }
public:
    explicit Parser(std::string_view input):s(input){if(s.size()>32768)invalid();utf8(s);}
    JsonObject parse(){JsonObject out;if(!take('{'))invalid();if(!take('}')){do{auto key=string();if(!take(':'))invalid();auto v=value();if(!out.emplace(std::move(key),std::move(v)).second)invalid();}while(take(','));if(!take('}'))invalid();}ws();if(at!=s.size())invalid();return out;}
};
}
JsonObject parse_state_json(std::string_view s){return Parser(s).parse();}
std::string encode_state_json(const JsonObject& object){
    std::string out="{\n";bool first=true;
    for(const auto& [key,value]:object){if(!first)out+=",\n";first=false;out+="  "+json_quote(key)+": ";
        if(auto text=std::get_if<std::string>(&value))out+=json_quote(*text);
        else if(auto integer=std::get_if<std::int64_t>(&value))out+=std::to_string(*integer);
        else if(auto flag=std::get_if<bool>(&value))out+=*flag?"true":"false";else out+="null";
    }out+="\n}\n";parse_state_json(out);return out;
}
std::string read_state_file(const std::filesystem::path& path,std::size_t limit){
    if(!std::filesystem::is_regular_file(std::filesystem::symlink_status(path)))throw std::runtime_error("STATE_FILE_INVALID");
    std::ifstream in(path,std::ios::binary);if(!in)throw std::runtime_error("STATE_FILE_UNREADABLE");
    std::string out;char c;while(in.get(c)){if(out.size()==limit)throw std::runtime_error("STATE_FILE_TOO_LARGE");out+=c;}
    if(!in.eof())throw std::runtime_error("STATE_FILE_UNREADABLE");
    return out;
}
void write_state_file(const std::filesystem::path& path,std::string_view contents){
    if(contents.size()>32768)throw std::runtime_error("STATE_FILE_TOO_LARGE");
    std::random_device random;
    std::string suffix=".";suffix+=std::to_string(random());suffix+='.';suffix+=std::to_string(random());suffix+=".tmp";
    auto tmp=path;tmp+=suffix;
    try{
        std::ofstream out(tmp,std::ios::binary|std::ios::trunc);out.exceptions(std::ios::badbit|std::ios::failbit);out.write(contents.data(),static_cast<std::streamsize>(contents.size()));out.close();
#ifdef _WIN32
        if(!MoveFileExW(tmp.c_str(),path.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("STATE_REPLACE_FAILED");
#else
        std::filesystem::rename(tmp,path);
#endif
    }catch(...){std::error_code ec;std::filesystem::remove(tmp,ec);throw;}
}
}
