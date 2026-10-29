#include "esurfing/protocol.hpp"
#include <iostream>

// Behavioral cases ported from legacy PortalRegression / PortalCompatibilityRegression.
// Synthetic .test addresses only; parsing does not perform network requests.
using namespace esurfing;
namespace {
unsigned checks=0;
const std::string auth="http://auth.test/auth.cgi";
const std::string ticket="http://ticket.test/ticket.cgi?wlanuserip=10.1.2.3&wlanacip=10.1.2.1&portal_node=test";
void check(bool b,const char* label){++checks;if(!b)throw std::runtime_error(label);}
std::string a(const std::string& value){return "<auth-url>"+value+"</auth-url>";}
std::string cdata(const std::string& value){return "<![CDATA["+value+"]]>";}
std::string t(const std::string& value=ticket){return "<ticket-url>"+cdata(value)+"</ticket-url>";}
Endpoints parses(const std::string& xml){auto value=portal_config("<!--//config.campus.js.chinatelecom.com\n"+xml+"\n//config.campus.js.chinatelecom.com-->");if(!value)throw Failure("PORTAL_EXPECTED");return *value;}
void accepts(const std::string& xml,const char* label){auto e=parses(xml);check(e.auth==auth&&e.ticket==ticket&&e.user_ip=="10.1.2.3"&&e.ac_ip=="10.1.2.1"&&e.source=="portal",label);}
void rejects(const std::string& xml,const char* label){bool rejected=false;try{parses(xml);}catch(const Failure&){rejected=true;}check(rejected,label);}
}
int main(){try{
    const auto fields=a(cdata(auth))+t();
    accepts(fields,"CDATA");
    accepts("<configuration><resources first=\"http://metadata.test/a?base=0&one=1&two=2\" second='http://metadata.test/b?base=0&three=3&four=4' label='synthetic > label'/>"+a(auth)+"<branches><branch>"+a(cdata(auth))+"</branch></branches>"+t()+"</configuration>","combined attributes and nested identical auth");
    for(const auto* markup:{"<item link=\"http://metadata.test/?first=1&second=2\"/>","<item link='http://metadata.test/?first=1&second=2'/>","<item label=\"a > b\" link='http://metadata.test/?first=1&second=2'/>","<item link=\"http://metadata.test/?first=1&amp;second=2&third=3\"/>"})accepts(markup+a(auth)+t(),"attribute query separators preserve endpoint bytes");
    for(const auto& first:{std::string("HTTP://AUTH.TEST:80/auth.cgi"),std::string("http://auth.test/parent/../auth.cgi"),std::string(" \n")+auth+"\t ",std::string("http://auth.test/auth.c&#103;&#x69;")})accepts(a(first)+"<nested>"+a(cdata(auth))+"</nested>"+t(),"normalized duplicate auth");
    accepts(a(auth)+a(auth)+a(auth)+t(),"three equal auth members");
    rejects(a(auth)+a(auth)+t()+t(),"equal auth cannot permit duplicate ticket");
    for(const auto& invalid:{std::string{},std::string(" \n\t "),std::string("<nested>")+auth+"</nested>",cdata("http://127.0.0.1/auth.cgi"),cdata("file:///auth.cgi"),cdata("http://untrusted@auth.test/auth.cgi"),cdata(auth+"#ignored"),cdata("/relative/auth.cgi"),cdata("http://auth.test/other.cgi")}){
        rejects(a(invalid)+a(cdata(auth))+t(),"invalid first duplicate member");rejects(a(cdata(auth))+a(invalid)+t(),"invalid last duplicate member");
    }
    for(const auto& different:{std::string("http://other.test/auth.cgi"),std::string("https://auth.test/auth.cgi"),std::string("http://auth.test:8080/auth.cgi"),std::string("http://auth.test/other/auth.cgi"),auth+"?mode=one"})rejects(a(cdata(auth))+"<nested>"+a(cdata(different))+"</nested>"+t(),"conflicting duplicate address");
    rejects(a(cdata(auth+"?mode=one"))+a(cdata(auth+"?mode=two"))+t(),"conflicting query values");
    rejects(a(cdata(auth+"?first=1&second=2"))+a(cdata(auth+"?second=2&first=1"))+t(),"query ordering remains significant");
    auto escaped=ticket;for(std::size_t at=0;(at=escaped.find('&',at))!=escaped.npos;at+=5)escaped.replace(at,1,"&amp;");
    accepts(a(auth)+"<ticket-url>"+escaped+"</ticket-url>","existing entities decoded once");
    auto numeric=ticket;for(std::size_t at=0;(at=numeric.find('&',at))!=numeric.npos;at+=5)numeric.replace(at,1,"&#38;");
    accepts(a(auth)+"<ticket-url>"+numeric+"</ticket-url>","numeric amp entity");
    accepts(a(auth)+"<ticket-url>"+ticket+"</ticket-url>","legacy raw text query separators");
    for(const auto* literal:{"a&amp;b=1","a&unknown;"}){auto value=ticket.substr(0,ticket.size()-4)+literal;check(parses(a(auth)+t(value)).ticket==value,"CDATA literal entity spelling preserved");}
    const auto once=auth+"?first=one&amp;second=two";
    check(parses(a(auth+"?first=one&amp;amp;second=two")+a(cdata(once))+t()).auth==once,"equality compares once decoded field values");
    accepts("<!-- synthetic &unknown; raw < text --><?synthetic opaque> query='a=1&b=2' ?>"+a(auth)+t(),"comment and PI excluded from amp rewriting");
    for(const auto* invalid:{"<item value=\"&unknown;\"/>","<item value='&#0;'/>","<item value=\"&#x110000;\"/>","<item value=\"&#xQ;\"/>","<item value=\"&amp\"/>","<item value='&#38'/>","<item value=\"raw&value\"/>","<item value=\"raw<value\"/>","<item value=\"one\" value='two'/>","<item value=unquoted/>","<item value=\"http://metadata.test/?first=1&second=2/>","<item></other>","<item></item value='a=1&b=2'>","<!DOCTYPE item [<!ENTITY fixture 'a=1&b=2'>]>","<!DOCTYPE item [<!ENTITY fixture SYSTEM 'file:///synthetic-never-read'>]>","<item>&unknown;</item>","<item>raw<value</item>"})rejects(invalid+a(auth)+t(),"strict malformed XML rejection");
    accepts("<?xml version=\"1.0\" encoding=\"UTF-8\"?><config>"+fields+"</config>","wrapped XML declaration");
    accepts(std::string("\xef\xbb\xbf")+"<?xml version=\"1.0\"?>"+fields,"BOM before XML declaration");
    accepts("<auth-url label=\"ignored > text\">"+auth+"</auth-url>"+t(),"quoted tag closure");
    accepts(a("http://<![CDATA[auth.test]]>/auth.cgi")+t(),"mixed text and CDATA");
    accepts(a("<![CDATA[http://auth.]]><![CDATA[test/auth.cgi]]>")+t(),"adjacent CDATA");
    accepts("<!--<auth-url>example</auth-url>-->"+fields,"commented example ignored");
    bool forbidden=false;try{safe_url("HTTP://LOCALHOST/auth.cgi");}catch(const Failure&){forbidden=true;}check(forbidden,"host case cannot bypass production loopback restriction");
    std::cout<<checks<<" legacy portal compatibility cases passed; pure parsing only\n";return 0;
}catch(const std::exception& e){std::cerr<<"case "<<(checks+1)<<": "<<e.what()<<'\n';return 1;}}
