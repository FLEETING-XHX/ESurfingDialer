#include "ClientServices.h"
#include <iostream>
#include <stdexcept>
using namespace esurfing;
void require(bool condition,const char* message) {if(!condition) throw std::runtime_error(message);}
int main() {
    PreviewServices service;
    require(!service.connect().ok,"preview must not authenticate");
    require(service.snapshot().phase==ConnectionPhase::unavailable,"no false online state");
    require(!service.saveAccount({L"",L"",L"",L""}).ok,"empty account rejected");
    require(service.saveAccount({L"",L"test",L"synthetic-user",L"synthetic-secret"}).ok,"add account");
    auto snapshot=service.snapshot();auto id=snapshot.selectedId;
    require(snapshot.accounts.size()==1 && !id.empty(),"first account selected");
    require(!service.selectAccount(L"missing").ok,"unknown selection rejected");
    require(service.saveAccount({id,L"updated",L"synthetic-user",L""}).ok,"edit account");
    Settings invalid;invalid.startHour=24;
    require(!service.saveSettings(invalid).ok,"hour boundary rejected");
    invalid.startHour=4;invalid.endHour=4;
    require(service.saveSettings(invalid).ok,"same hour is a fixed-time schedule");
    invalid.endHour=24;require(service.saveSettings(invalid).ok,"end of day accepted");
    invalid.endHour=3;require(!service.saveSettings(invalid).ok,"reverse window rejected");
    Settings settings;settings.startMinimized=true;
    require(service.saveSettings(settings).ok && !service.snapshot().settings.startMinimized,"startup invariant");
    require(!service.perform(ServiceAction::exportLogs).ok,"no false export success");
    require(service.deleteAccount(id).ok && service.snapshot().selectedId.empty(),"delete selected account");
    require(!service.deleteAccount(id).ok,"unknown delete rejected");
    PreviewServices fresh;require(fresh.snapshot().accounts.empty(),"no persisted accounts");
    std::cout<<"Service contract boundaries passed\n";
}
