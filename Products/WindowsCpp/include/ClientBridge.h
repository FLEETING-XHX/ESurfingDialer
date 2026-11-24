#pragma once
#include <QObject>
#include <QTimer>
#include <QVariantMap>
#include "ClientServices.h"
namespace esurfing {
class ClientBridge final : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY stateChanged)
    Q_PROPERTY(QString notice READ notice NOTIFY noticeChanged)
public:
    explicit ClientBridge(IClientServices& service,QObject* parent=nullptr);
    QVariantMap state() const;
    QString notice() const { return notice_; }
    Q_INVOKABLE bool saveAccount(QString id,QString label,QString username,QString password);
    Q_INVOKABLE bool selectAccount(QString id);
    Q_INVOKABLE bool deleteAccount(QString id);
    Q_INVOKABLE bool saveSettings(QVariantMap settings);
    Q_INVOKABLE void toggleConnection();
    Q_INVOKABLE void action(QString name);
    void refresh();
signals:
    void stateChanged();
    void noticeChanged();
private:
    bool finish(Result result);
    IClientServices& service_;
    Snapshot snapshot_;
    QString notice_;
    QTimer poll_;
};
}
