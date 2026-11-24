#include "ClientBridge.h"
#include <windows.h>
namespace esurfing {
static QString q(const std::wstring& value){return QString::fromStdWString(value);}
ClientBridge::ClientBridge(IClientServices& service,QObject* parent):QObject(parent),service_(service),snapshot_(service.snapshot()) {
    connect(&poll_,&QTimer::timeout,this,&ClientBridge::refresh);poll_.start(1000);
}
void ClientBridge::refresh(){auto next=service_.snapshot();if(next!=snapshot_){snapshot_=std::move(next);emit stateChanged();}}
QVariantMap ClientBridge::state() const {
    QVariantList accounts,logs,down,up;QString summary=QStringLiteral("尚未配置账户");
    for(const auto& a:snapshot_.accounts){
        accounts.append(QVariantMap{{"id",q(a.id)},{"label",q(a.label)},{"username",q(a.username)}});
        if(a.id==snapshot_.selectedId)summary=q(a.label.empty()?a.username:a.label);
    }
    for(const auto& l:snapshot_.logs)logs.append(q(l));
    for(double v:snapshot_.traffic.download)down.append(v);
    for(double v:snapshot_.traffic.upload)up.append(v);
    const auto& s=snapshot_.settings;
    QString phase=QStringLiteral("尚未接入");
    switch(snapshot_.phase){case ConnectionPhase::offline:phase=QStringLiteral("未连接");break;
    case ConnectionPhase::connecting:phase=QStringLiteral("连接中");break;case ConnectionPhase::online:phase=QStringLiteral("已连接");break;
    case ConnectionPhase::recovering:phase=QStringLiteral("恢复中");break;case ConnectionPhase::stopping:phase=QStringLiteral("正在停止");break;default:break;}
    return {{"accounts",accounts},{"selectedId",q(snapshot_.selectedId)},{"accountSummary",summary},
        {"phase",phase},{"status",q(snapshot_.status)},{"online",snapshot_.phase==ConnectionPhase::online},{"logs",logs},
        {"settings",QVariantMap{{"autoStart",s.autoStart},{"startMinimized",s.startMinimized},{"autoConnect",s.autoConnect},
            {"enhancedConnection",s.enhancedConnection},{"backgroundNotice",s.backgroundNotice},{"dailyReauth",s.dailyReauth},
            {"startHour",s.startHour},{"endHour",s.endHour}}},
        {"traffic",QVariantMap{{"available",snapshot_.traffic.available},{"downloadKB",snapshot_.traffic.downloadKB},
            {"uploadKB",snapshot_.traffic.uploadKB},{"download",down},{"upload",up}}}};
}
bool ClientBridge::finish(Result result){
    // A user retry must show feedback again even if it yields the same message.
    // Idle snapshot polling never emits this signal.
    notice_=q(result.message);emit noticeChanged();
    refresh();return result.ok;
}
bool ClientBridge::saveAccount(QString id,QString label,QString username,QString password){
    AccountInput input{id.toStdWString(),label.toStdWString(),username.toStdWString(),password.toStdWString()};
    auto result=service_.saveAccount(input);
    if(!input.password.empty())SecureZeroMemory(input.password.data(),input.password.size()*sizeof(wchar_t));
    password.fill(QChar(0));return finish(std::move(result));
}
bool ClientBridge::selectAccount(QString id){return finish(service_.selectAccount(id.toStdWString()));}
bool ClientBridge::deleteAccount(QString id){return finish(service_.deleteAccount(id.toStdWString()));}
bool ClientBridge::saveSettings(QVariantMap values){
    auto s=snapshot_.settings;
    s.autoStart=values.value("autoStart",s.autoStart).toBool();s.startMinimized=values.value("startMinimized",s.startMinimized).toBool();
    s.autoConnect=values.value("autoConnect",s.autoConnect).toBool();s.enhancedConnection=values.value("enhancedConnection",s.enhancedConnection).toBool();
    s.backgroundNotice=values.value("backgroundNotice",s.backgroundNotice).toBool();s.dailyReauth=values.value("dailyReauth",s.dailyReauth).toBool();
    bool startOk=true,endOk=true;
    if(values.contains("startHour"))s.startHour=values.value("startHour").toInt(&startOk);
    if(values.contains("endHour"))s.endHour=values.value("endHour").toInt(&endOk);
    if(!startOk||!endOk)return finish({false,L"请输入有效的整点时间。"});
    return finish(service_.saveSettings(s));
}
void ClientBridge::toggleConnection(){
    const auto phase = service_.snapshot().phase;
    const bool active = phase==ConnectionPhase::online || phase==ConnectionPhase::connecting ||
        phase==ConnectionPhase::recovering || phase==ConnectionPhase::stopping;
    finish(active ? service_.disconnect() : service_.connect());
}
void ClientBridge::action(QString name){
    if(name=="export")finish(service_.perform(ServiceAction::exportLogs));
    else if(name=="password")finish(service_.perform(ServiceAction::changePassword));
    else if(name=="recharge")finish(service_.perform(ServiceAction::recharge));
    else if(name=="project")finish(service_.perform(ServiceAction::projectHome));
    else finish({false,L"未知操作。"});
}
}
