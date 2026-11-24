#include "ClientServices.h"
#include <algorithm>
namespace esurfing {
PreviewServices::PreviewServices() {
    state_.logs.push_back(L"Windows C++ 界面已启动；认证服务尚未接入。");
    state_.logs.push_back(L"账户仅保留本次运行的备注与账号；密码不保存。");
}
Snapshot PreviewServices::snapshot() const { return state_; }
Result PreviewServices::connect() { return {false,L"认证接口尚未接入，当前不会发起校园网认证。"}; }
Result PreviewServices::disconnect() { return {false,L"认证接口尚未接入，当前没有认证会话。"}; }
Result PreviewServices::saveAccount(const AccountInput& in) {
    if(in.username.empty() || in.username.size()>128 || in.label.size()>40 || in.password.size()>256)
        return {false,L"请填写有效账号，检查备注和密码长度。"};
    auto it=std::find_if(state_.accounts.begin(),state_.accounts.end(),[&](auto& a){return a.id==in.id;});
    if(!in.id.empty() && it==state_.accounts.end()) return {false,L"账户不存在，请重新选择。"};
    auto id=in.id;
    if(id.empty()){
        do {id=std::to_wstring(nextId_++);}
        while(std::any_of(state_.accounts.begin(),state_.accounts.end(),[&](const auto& a){return a.id==id;}));
    }
    Account account{id,in.label,in.username};
    if(it==state_.accounts.end()) state_.accounts.push_back(account); else *it=account;
    if(state_.selectedId.empty()) state_.selectedId=account.id;
    return {true,L"已保存本次预览的账号信息，密码未保存。"};
}
Result PreviewServices::selectAccount(const std::wstring& id) {
    if(std::none_of(state_.accounts.begin(),state_.accounts.end(),[&](auto& a){return a.id==id;})) return {false,L"账户不存在。"};
    state_.selectedId=id; return {true,L"已切换当前账户。"};
}
Result PreviewServices::deleteAccount(const std::wstring& id) {
    const auto old=state_.accounts.size();
    std::erase_if(state_.accounts,[&](auto& a){return a.id==id;});
    if(old==state_.accounts.size()) return {false,L"账户不存在。"};
    if(state_.selectedId==id) state_.selectedId=state_.accounts.empty()?L"":state_.accounts.front().id;
    return {true,L"已删除本次预览的账户。"};
}
Result PreviewServices::saveSettings(const Settings& settings) {
    if(settings.startHour<0 || settings.startHour>23 || settings.endHour<0 || settings.endHour>24 || settings.endHour<settings.startHour)
        return {false,L"开始时间为 00–23 点，结束时间为 00–24 点，结束不能早于开始。"};
    state_.settings=settings;
    if(!settings.autoStart) state_.settings.startMinimized=false;
    return {true,L"已保存本次预览设置；系统自启与认证行为尚未接入。"};
}
Result PreviewServices::perform(ServiceAction) { return {false,L"该服务接口尚未接入。"}; }
}
