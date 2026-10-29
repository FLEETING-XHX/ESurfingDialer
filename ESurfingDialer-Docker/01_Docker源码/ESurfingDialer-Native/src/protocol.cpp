#include "esurfing/protocol.hpp"
#include <curl/curl.h>
#include <libxml/parser.h>
#include <libxml/tree.h>
#include <charconv>
#include <memory>
#include <set>
#include <algorithm>
extern "C" {
#include "utils/sim/SimEvp.h"
}

namespace esurfing {
using Url = std::unique_ptr<CURLU,decltype(&curl_url_cleanup)>;
using Doc = std::unique_ptr<xmlDoc,decltype(&xmlFreeDoc)>;
static std::string trim(std::string_view v) {
    auto first=v.find_first_not_of(" \r\n\t");
    if(first==v.npos) return {};
    return std::string(v.substr(first,v.find_last_not_of(" \r\n\t")-first+1));
}
static std::string part(CURLU* u,CURLUPart p,unsigned flags=0) {
    char* v=nullptr;
    if(curl_url_get(u,p,&v,flags)!=CURLUE_OK) return {};
    std::string result(v); curl_free(v); return result;
}
std::string safe_url(std::string_view value,bool local) {
    if(value.empty()||value.size()>8192||value.find_first_of("\r\n\t#")!=value.npos||value.find('\0')!=value.npos) throw Failure("URL_INVALID");
    Url u(curl_url(),curl_url_cleanup);
    auto s=std::string(value);
    if(!u||curl_url_set(u.get(),CURLUPART_URL,s.c_str(),0)!=CURLUE_OK) throw Failure("URL_INVALID");
    auto scheme=part(u.get(),CURLUPART_SCHEME),host=part(u.get(),CURLUPART_HOST);
    for(auto* value:{&scheme,&host})for(char& c:*value)if(c>='A'&&c<='Z')c=static_cast<char>(c-'A'+'a');
    if((scheme!="http"&&scheme!="https")||host.empty()||!part(u.get(),CURLUPART_USER).empty()
       ||!part(u.get(),CURLUPART_PASSWORD).empty()||!part(u.get(),CURLUPART_FRAGMENT).empty()) throw Failure("URL_INVALID");
    if(!local&&(host=="localhost"||host=="[::1]"||host=="0.0.0.0"||host.starts_with("127.")||host.starts_with("169.254."))) throw Failure("URL_FORBIDDEN");
    if(curl_url_set(u.get(),CURLUPART_SCHEME,scheme.c_str(),0)!=CURLUE_OK||curl_url_set(u.get(),CURLUPART_HOST,host.c_str(),0)!=CURLUE_OK)throw Failure("URL_INVALID");
    return part(u.get(),CURLUPART_URL,CURLU_NO_DEFAULT_PORT);
}
std::string resolve_url(std::string_view base,std::string_view relative,bool local) {
    Url u(curl_url(),curl_url_cleanup);
    auto b=safe_url(base,local),r=std::string(relative);
    if(curl_url_set(u.get(),CURLUPART_URL,b.c_str(),0)!=CURLUE_OK
       ||curl_url_set(u.get(),CURLUPART_URL,r.c_str(),0)!=CURLUE_OK) throw Failure("URL_INVALID");
    return safe_url(part(u.get(),CURLUPART_URL),local);
}
std::map<std::string,std::vector<std::string>> query_parameters(std::string_view value) {
    Url u(curl_url(),curl_url_cleanup); auto s=std::string(value);
    if(curl_url_set(u.get(),CURLUPART_URL,s.c_str(),0)!=CURLUE_OK) throw Failure("URL_INVALID");
    std::string q=part(u.get(),CURLUPART_QUERY);
    std::map<std::string,std::vector<std::string>> result;
    std::size_t at=0;
    while(at<q.size()) {
        auto end=q.find('&',at); if(end==q.npos) end=q.size();
        auto item=q.substr(at,end-at); auto split=item.find('=');
        if(split!=item.npos) {
            auto decode=[](std::string v){std::replace(v.begin(),v.end(),'+',' '); int length=0; char* out=curl_easy_unescape(nullptr,v.c_str(),static_cast<int>(v.size()),&length);
                if(!out) throw Failure("QUERY_INVALID"); std::string r(out,static_cast<std::size_t>(length));curl_free(out);
                if(r.find('\0')!=r.npos) throw Failure("QUERY_INVALID"); return r;};
            result[decode(item.substr(0,split))].push_back(decode(item.substr(split+1)));
        }
        at=end+1;
    }
    return result;
}
bool ipv4(std::string_view s) {
    unsigned count=0;std::size_t at=0;
    while(at<=s.size()) {auto end=s.find('.',at); if(end==s.npos)end=s.size();auto p=s.substr(at,end-at);
        unsigned value=0;auto [ptr,ec]=std::from_chars(p.data(),p.data()+p.size(),value);
        if(p.empty()||p.size()>3||ec!=std::errc{}||ptr!=p.data()+p.size()||value>255)return false;
        ++count;if(end==s.size())break;at=end+1;
    }return count==4;
}
std::string xml_escape(std::string_view value) {
    std::string result;
    for(unsigned char c:value) switch(c) {
    case '&':result+="&amp;";break;case '<':result+="&lt;";break;case '>':result+="&gt;";break;
    case '\'':result+="&apos;";break;case '"':result+="&quot;";break;
    default:if(c<32&&c!='\n'&&c!='\r'&&c!='\t')throw Failure("XML_INPUT_INVALID");result+=static_cast<char>(c);
    }return result;
}
std::string request_xml(const std::vector<std::pair<std::string,std::string>>& fields) {
    std::string out="<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<request>\n";
    for(auto& [name,value]:fields) {
        if(name.empty()||name[0]<'a'||name[0]>'z'||name.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789-")!=name.npos)throw Failure("XML_FIELD_INVALID");
        out+="    <"+name+">"+xml_escape(value)+"</"+name+">\n";
    }return out+"</request>";
}
static Doc parse(std::string_view s,std::size_t limit=65536) {
    if(s.empty()||s.size()>limit||s.find('\0')!=s.npos||s.find("<!DOCTYPE")!=s.npos||s.find("<!ENTITY")!=s.npos)throw Failure("XML_INVALID");
    Doc doc(xmlReadMemory(s.data(),static_cast<int>(s.size()),nullptr,"UTF-8",XML_PARSE_NONET|XML_PARSE_NOERROR|XML_PARSE_NOWARNING),xmlFreeDoc);
    if(!doc||doc->intSubset||doc->extSubset||!xmlDocGetRootElement(doc.get()))throw Failure("XML_INVALID");
    return doc;
}
static void find(xmlNode* node,std::string_view name,std::vector<xmlNode*>& out,unsigned depth=0) {
    if(depth>64) throw Failure("XML_DEPTH_INVALID");
    for(;node;node=node->next)if(node->type==XML_ELEMENT_NODE) {
        if(name==reinterpret_cast<const char*>(node->name)){if(node->ns)throw Failure("XML_FIELDS_INVALID");out.push_back(node);}
        find(node->children,name,out,depth+1);
    }
}
static std::string text(xmlNode* node) {
    std::string result;
    for(auto p=node->children;p;p=p->next) {
        if(p->type==XML_TEXT_NODE||p->type==XML_CDATA_SECTION_NODE) {if(p->content)result+=reinterpret_cast<const char*>(p->content);}
        else if(p->type!=XML_COMMENT_NODE)throw Failure("XML_FIELDS_INVALID");
    }
    result=trim(result);if(result.empty())throw Failure("XML_FIELDS_INVALID");return result;
}
static std::string field(xmlNode* root,std::string_view name) {
    std::vector<xmlNode*> nodes;find(root->children,name,nodes);
    if(nodes.size()!=1||nodes[0]->parent!=root)throw Failure("XML_FIELDS_INVALID");
    return text(nodes[0]);
}
static Seconds interval(const std::string& value,Seconds maximum) {
    unsigned long long n=0;auto [p,ec]=std::from_chars(value.data(),value.data()+value.size(),n);
    if(ec!=std::errc{}||p!=value.data()+value.size()||!n)throw Failure("INTERVAL_INVALID");
    if(maximum<Seconds(30)||maximum>Seconds(600))throw Failure("INTERVAL_POLICY_INVALID");
    return Seconds(std::clamp(n,5ull,static_cast<unsigned long long>(maximum.count())));
}
std::string response_ticket(std::string_view s){auto doc=parse(s);return field(xmlDocGetRootElement(doc.get()),"ticket");}
Login response_login(std::string_view s,bool local,Seconds maximum){auto doc=parse(s);auto root=xmlDocGetRootElement(doc.get());
    return {safe_url(field(root,"keep-url"),local),safe_url(field(root,"term-url"),local),interval(field(root,"keep-retry"),maximum)};}
Seconds response_keep(std::string_view s,Seconds maximum){auto doc=parse(s);return interval(field(xmlDocGetRootElement(doc.get()),"interval"),maximum);}
static std::string query_amp(std::string_view xml) {
    std::string out; bool tag=false;char quote=0;
    for(std::size_t i=0;i<xml.size();) {
        std::string_view close;
        if(xml.substr(i).starts_with("<![CDATA["))close="]]>";
        else if(xml.substr(i).starts_with("<!--"))close="-->";
        else if(xml.substr(i).starts_with("<?"))close="?>";
        if(!close.empty()){auto end=xml.find(close,i+2);if(end==xml.npos)throw Failure("PORTAL_XML_INVALID");end+=close.size();out+=xml.substr(i,end-i);i=end;continue;}
        char c=xml[i];
        if(c=='<'&&!quote)tag=true;
        if(tag&&(c=='\''||c=='"')){if(!quote)quote=c;else if(quote==c)quote=0;}
        if(c=='>'&&!quote)tag=false;
        if(c=='&'&&(!tag||quote)) {
            std::size_t end=i+1;
            while(end<xml.size()&&((xml[end]>='a'&&xml[end]<='z')||(xml[end]>='A'&&xml[end]<='Z')||(xml[end]>='0'&&xml[end]<='9')||xml[end]=='_'||xml[end]=='-'||xml[end]=='.'||xml[end]=='~'))++end;
            if(end>i+1&&end<xml.size()&&xml[end]=='='){out+="&amp;";++i;continue;}
        }out+=c;++i;
    }return out;
}
std::optional<Endpoints> portal_config(std::string_view body,bool local) {
    constexpr std::string_view start="<!--//config.campus.js.chinatelecom.com",end="//config.campus.js.chinatelecom.com-->";
    if(body.size()>128*1024)throw Failure("PORTAL_BODY_TOO_LARGE");
    auto first=body.find(start);if(first==body.npos)return {};
    if(body.find(start,first+start.size())!=body.npos)throw Failure("PORTAL_CONFIG_DUPLICATE");
    auto last=body.find(end,first+start.size());if(last==body.npos)throw Failure("PORTAL_CONFIG_TRUNCATED");
    auto config=trim(body.substr(first+start.size(),last-first-start.size()));
    if(config.starts_with("\xef\xbb\xbf"))config=trim(std::string_view(config).substr(3));
    if(config.starts_with("<?xml")){auto d=config.find("?>");if(d==config.npos)throw Failure("XML_INVALID");config=config.substr(d+2);}
    auto doc=parse("<portal-config>"+query_amp(config)+"</portal-config>",160*1024);auto root=xmlDocGetRootElement(doc.get());
    std::vector<xmlNode*> auth,ticket;find(root->children,"auth-url",auth);find(root->children,"ticket-url",ticket);
    if(auth.empty()||ticket.size()!=1)throw Failure("PORTAL_FIELDS_INVALID");
    std::set<std::string> urls;
    for(auto n:auth)urls.insert(safe_url(text(n),local));
    if(urls.size()!=1)throw Failure("PORTAL_AUTH_CONFLICT");
    auto t=safe_url(text(ticket[0]),local); auto a=*urls.begin();
    auto has_path=[](const std::string& url,const char* suffix){Url u(curl_url(),curl_url_cleanup);curl_url_set(u.get(),CURLUPART_URL,url.c_str(),0);return part(u.get(),CURLUPART_PATH).ends_with(suffix);};
    if(!has_path(a,"/auth.cgi")||!has_path(t,"/ticket.cgi"))throw Failure("PORTAL_ENDPOINT_PATH_INVALID");
    auto q=query_parameters(t);
    if(q["wlanuserip"].size()!=1||q["wlanacip"].size()!=1||!ipv4(q["wlanuserip"][0])||!ipv4(q["wlanacip"][0]))throw Failure("PORTAL_IP_INVALID");
    return Endpoints{a,t,q["wlanuserip"][0],q["wlanacip"][0],"portal"};
}
std::string checksum(std::string_view bytes) {
    auto ctx=std::unique_ptr<EVP_MD_CTX,decltype(&EVP_MD_CTX_free)>(EVP_MD_CTX_new(),EVP_MD_CTX_free);
    unsigned char digest[EVP_MAX_MD_SIZE];unsigned length=0;
    if(!ctx||EVP_DigestInit_ex(ctx.get(),EVP_md5(),nullptr)!=1||EVP_DigestUpdate(ctx.get(),bytes.data(),bytes.size())!=1||EVP_DigestFinal_ex(ctx.get(),digest,&length)!=1||length!=16)throw Failure("CHECKSUM_FAILED");
    std::string result;const char* hex="0123456789abcdef";for(unsigned i=0;i<length;++i){result+=hex[digest[i]>>4];result+=hex[digest[i]&15];}return result;
}
}
