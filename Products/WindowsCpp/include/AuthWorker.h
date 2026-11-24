#pragma once

#include "AuthSession2104.h"
#include "CampusPortal.h"
#include "ClientServices.h"
#include <mutex>
#include <thread>

namespace esurfing::auth2104 {
// Commands and destruction are owned by the UI thread. The session and both transports
// are created and used exclusively by the worker.
class Worker {
public:
    struct Dependencies {
        CipherFactory cipher;
        std::function<PortalEndpoints(std::stop_token)> discover;
        std::function<std::unique_ptr<Transport>(std::stop_token)> transport;
        Channel channel = Channel::windows2104;
    };
    struct State {
        ConnectionPhase phase = ConnectionPhase::offline;
        std::wstring status = L"等待连接。";
        std::vector<std::wstring> logs;
    };

    explicit Worker(Dependencies dependencies);
    bool available() const;
    State state() const;
    Result start(QString clientId, QString mac, QString username, std::function<QString()> readPassword);
    Result stop();

private:
    void publish(ConnectionPhase phase, const wchar_t* message);
    void run(std::stop_token stop, QString clientId, QString mac, const QString& username,
             const std::function<QString()>& readPassword);

    Dependencies dependencies_;
    mutable std::mutex mutex_;
    State state_;
    bool running_ = false;
    // Destroy first, before the worker's state and dependencies.
    std::jthread thread_;
};

Worker::Dependencies productionAuth(CipherFactory cipher = {}, Channel channel = Channel::windows2104);
}
