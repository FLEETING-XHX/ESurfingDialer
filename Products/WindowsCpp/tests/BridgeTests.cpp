#include <QtTest>
#include "ClientBridge.h"
using namespace esurfing;
class BridgeTests:public QObject {
    Q_OBJECT
private slots:
    void idleDoesNotNotify(){PreviewServices service;ClientBridge bridge(service);QSignalSpy spy(&bridge,&ClientBridge::stateChanged);for(int i=0;i<20;i++)bridge.refresh();QTest::qWait(1100);QCOMPARE(spy.count(),0);}
    void accountsAndSecrets(){
        PreviewServices service;ClientBridge bridge(service);QSignalSpy spy(&bridge,&ClientBridge::stateChanged);
        QVERIFY(bridge.saveAccount("","Test","synthetic-user","synthetic-secret"));QCOMPARE(spy.count(),1);
        auto state=bridge.state();QCOMPARE(state.value("accounts").toList().size(),1);
        auto entry=state.value("accounts").toList().first().toMap();QVERIFY(!entry.contains("password"));
        QVERIFY(!bridge.notice().contains("synthetic-secret"));
        bridge.refresh();QCOMPARE(spy.count(),1);
        QVERIFY(bridge.deleteAccount(entry.value("id").toString()));QCOMPARE(spy.count(),2);
    }
    void invalidSettingsDoNotMutate(){PreviewServices service;ClientBridge bridge(service);auto before=bridge.state();QVERIFY(!bridge.saveSettings({{"startHour","bad"}}));QCOMPARE(bridge.state(),before);QVERIFY(!bridge.saveSettings({{"startHour",24}}));QCOMPARE(bridge.state(),before);}
    void unsupportedNeverOnline(){PreviewServices service;ClientBridge bridge(service);bridge.toggleConnection();QVERIFY(!bridge.state().value("online").toBool());QVERIFY(!bridge.notice().isEmpty());}
};
QTEST_GUILESS_MAIN(BridgeTests)
#include "BridgeTests.moc"
