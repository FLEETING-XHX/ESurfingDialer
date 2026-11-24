#pragma once

#include <QByteArray>
#include <QLockFile>
#include <QMap>
#include <QString>
#include "AuthWorker.h"
#include "ClientServices.h"

namespace esurfing {
// Owned by the Qt UI thread. Files are bounded and replaced atomically.
class LocalClientServices final : public IClientServices {
public:
    explicit LocalClientServices(const QString& directory,
        auth2104::Worker::Dependencies authentication = auth2104::productionAuth());
    Snapshot snapshot() const override;
    Result connect() override;
    Result disconnect() override;
    Result saveAccount(const AccountInput& input) override;
    Result selectAccount(const std::wstring& id) override;
    Result deleteAccount(const std::wstring& id) override;
    Result saveSettings(const Settings& settings) override;
    Result perform(ServiceAction action) override;
private:
    using Credentials = QMap<QString, QByteArray>;
    bool load();
    bool commit(PreviewServices candidate, Credentials credentials);
    Result storageFailure() const;

    QString path_;
    QLockFile lock_;
    PreviewServices model_;
    Credentials credentials_;
    std::wstring storageError_;
    auth2104::Worker authentication_;
};
}
