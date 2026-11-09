#include "esurfing/link.hpp"
#include <fstream>
#include <sstream>
#include <set>
#include <limits>
namespace esurfing {
namespace {
bool interface_name(const std::string& name){return name.size()<=64&&name.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.:-")==name.npos;}
}
LinkMonitor::LinkMonitor(std::filesystem::path root,std::string wan,std::string physical,std::filesystem::path route)
    :root_(std::move(root)),wan_(std::move(wan)),physical_(std::move(physical)),route_(std::move(route)){
    if(!interface_name(wan_)||!interface_name(physical_))throw std::invalid_argument("WAN_INTERFACE_INVALID");
}
std::string UplinkSnapshot::signature()const{return logical_interface+"/"+std::to_string(logical_link)+"/"+interface_name+"/"+std::to_string(link)+"/"+std::to_string(configured)+"/"+std::to_string(physical)+"/"+std::to_string(ipv4_default)+"/"+std::to_string(route_readable);}
std::string UplinkSnapshot::describe()const{
    return std::string(configured?(physical?"已指定WAN物理接口=":"已指定WAN逻辑接口="):"IPv4默认出口候选（未确认WAN）=")+
        (interface_name.empty()?"未识别":interface_name)+"，"+(link==up?"链路已连接":link==down?"链路未连接":link==missing?"接口不存在":"链路状态未知");
}
std::string uplink_diagnosis(const UplinkSnapshot& u,Mono now){
    if(u.observed==Mono{}||now<u.observed||now-u.observed>Seconds(10))return "[UPLINK_EVIDENCE_STALE] 接口证据缺失或已过期，外网可达性未确认，不能定位WAN故障";
    auto context=u.describe()+"；";
    if(u.configured&&u.link==UplinkSnapshot::down)return context+(u.physical?"[WAN_PHYSICAL_LINK_DOWN] WAN物理链路断开，请检查网线及上游端口；尚不能认定网线损坏":"[WAN_LOGICAL_LINK_DOWN] WAN逻辑接口未连接，请检查拨号及接口状态，不能据此认定网线断开");
    if(u.configured&&u.link==UplinkSnapshot::missing)return context+"[WAN_INTERFACE_MISSING] 指定接口不存在，请检查接口配置或设备状态";
    if(!u.configured&&u.link==UplinkSnapshot::down)return context+"[UPLINK_LINK_DOWN_SUSPECTED] 疑似出口链路异常，尚未确认此接口是否为WAN";
    if(u.physical&&!u.logical_interface.empty()&&(u.logical_link==UplinkSnapshot::down||u.logical_link==UplinkSnapshot::missing))return context+"[WAN_LOGICAL_LINK_DOWN] WAN逻辑接口="+u.logical_interface+" 未建立，请检查拨号或接口配置，不能认定物理线路断开";
    if(u.link==UplinkSnapshot::up)return context+"[UPLINK_REACHABILITY_UNCONFIRMED] 链路已连接，但联网检测失败；请结合下方DNS、超时及HTTP结果排查上游网络或探测端点";
    return context+"[UPLINK_CAUSE_UNKNOWN] 外网可达性未确认，故障原因未知；IPv6及策略路由需另查";
}
std::vector<std::string> LinkMonitor::poll(){
    auto old_uplink=uplink_.signature();
    std::ifstream routes(route_);std::string row;std::getline(routes,row);
    std::set<std::string> defaults;unsigned best=std::numeric_limits<unsigned>::max();
    while(std::getline(routes,row)){
        std::istringstream in(row);std::string name,destination,gateway,mask;unsigned flags=0,refs=0,use=0,metric=0;
        if(in>>name>>destination>>gateway>>std::hex>>flags>>std::dec>>refs>>use>>metric>>mask){
            if(!name.empty()&&interface_name(name)&&destination=="00000000"&&mask=="00000000"&&(flags&1)&&!(flags&0x200)){
                if(metric<best){defaults.clear();best=metric;}if(metric==best)defaults.insert(name);
            }
        }
    }
    uplink_={};uplink_.route_readable=routes.is_open();uplink_.ipv4_default=!defaults.empty();
    if(defaults.size()==1)candidate_=*defaults.begin();
    uplink_.configured=!wan_.empty()||!physical_.empty();uplink_.physical=!physical_.empty();
    uplink_.interface_name=!physical_.empty()?physical_:!wan_.empty()?wan_:defaults.size()>1?std::string{}:candidate_;
    uplink_.observed=std::chrono::steady_clock::now();
    auto read_link=[&](const std::string& name){
        auto value=UplinkSnapshot::unknown;if(name.empty())return value;
        try{
            auto path=root_/name;
            if(!std::filesystem::exists(path))return UplinkSnapshot::missing;
            std::string carrier;std::ifstream(path/"carrier")>>carrier;
            if(carrier=="0")value=UplinkSnapshot::down;else if(carrier=="1")value=UplinkSnapshot::up;
        }catch(const std::exception&){}
        return value;
    };
    uplink_.link=read_link(uplink_.interface_name);
    uplink_.logical_interface=wan_;uplink_.logical_link=read_link(wan_);

    std::vector<std::string> changes;std::map<std::string,std::string> current;
    try{
        for(const auto& entry:std::filesystem::directory_iterator(root_)){
            auto name=entry.path().filename().string();
            if(name=="lo"||name.empty()||name.size()>64||name.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.:-")!=name.npos)continue;
            std::string state,carrier;std::ifstream(entry.path()/"operstate")>>state;std::ifstream(entry.path()/"carrier")>>carrier;
            if(state!="up"&&state!="down"&&state!="lowerlayerdown"&&state!="dormant"&&state!="unknown")state="unknown";
            if(carrier!="0"&&carrier!="1")carrier="unknown";
            bool wireless=std::filesystem::exists(entry.path()/"wireless");
            auto value=state+"/"+carrier+(wireless?"/wireless":"/other");current[name]=value;
            if(!previous_.contains(name)||previous_.at(name)!=value){
                auto status=carrier=="0"?"链路未连接":carrier=="1"?"链路已连接":"链路状态未知";
                auto operating=state=="up"?"启用":state=="down"?"关闭":state=="lowerlayerdown"?"下层链路断开":state=="dormant"?"等待连接":"未知";
                changes.push_back("[INTERFACE_STATE] 接口="+name+" 类型="+(wireless?"无线":"未确认")+" 状态="+operating+"，"+status+"；是否为上网接口及故障根因需确认");
            }
        }
        for(const auto& [name,value]:previous_)if(!current.contains(name))changes.push_back("[INTERFACE_STATE] 接口="+name+" 已消失，需检查接口配置或设备状态");
        previous_=std::move(current);unavailable_=false;
    }catch(const std::exception&){if(!unavailable_)changes.push_back("[INTERFACE_UNAVAILABLE] 无法读取网络接口状态，不能据此判断WAN或WLAN是否断开");unavailable_=true;}
    if(old_uplink!=uplink_.signature())changes.push_back("[UPLINK_STATE] "+uplink_.describe());
    return changes;
}
}
