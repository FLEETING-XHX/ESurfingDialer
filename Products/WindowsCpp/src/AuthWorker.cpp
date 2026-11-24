#include "AuthWorker.h"
#include "AuthHttpTransport.h"

#include <QDateTime>
#include <condition_variable>
#include <string_view>
#include <windows.h>

using namespace std::chrono_literals;

namespace esurfing::auth2104 {
namespace {
bool wait(std::stop_token stop, std::chrono::seconds delay) {
    std::mutex mutex;
    std::unique_lock lock(mutex);
    std::condition_variable_any timer;
    timer.wait_for(lock, stop, delay, [] { return false; });
    return !stop.stop_requested();
}

struct Password {
    QString value;
    ~Password() {
        if (!value.isEmpty()) SecureZeroMemory(value.data(), value.size() * sizeof(QChar));
    }
};
}

Worker::Dependencies productionAuth(CipherFactory cipher, Channel channel) {
    return {std::move(cipher), [channel](std::stop_token stop) {
        HttpTransport probe(stop);
        return discoverPortal(QUrl(QStringLiteral("http://www.gstatic.com/generate_204")),
                              [&](const Request& request) { return probe.get(request); }, channel);
    }, [](std::stop_token stop) { return std::make_unique<HttpTransport>(stop); }, channel};
}

Worker::Worker(Dependencies dependencies) : dependencies_(std::move(dependencies)) {}

bool Worker::available() const { return dependencies_.cipher && dependencies_.discover && dependencies_.transport; }

Worker::State Worker::state() const {
    std::lock_guard lock(mutex_);
    return state_;
}

Result Worker::start(QString clientId, QString mac, QString username, std::function<QString()> readPassword) {
    if (!available()) return {false, L"当前认证通道的原生加解密组件不可用。"};
    {
        std::lock_guard lock(mutex_);
        if (running_) return {false, L"正在处理认证会话。"};
        running_ = true;
        state_.phase = ConnectionPhase::connecting;
        state_.status = L"正在查找校园网认证门户。";
    }
    try {
        thread_ = std::jthread([this, clientId = std::move(clientId), mac = std::move(mac),
                                username = std::move(username), readPassword = std::move(readPassword)]
                               (std::stop_token stop) mutable {
            run(stop, std::move(clientId), std::move(mac), username, readPassword);
        });
    } catch (...) {
        std::lock_guard lock(mutex_);
        running_ = false;
        state_.phase = ConnectionPhase::offline;
        state_.status = L"无法启动认证线程。";
        return {false, state_.status};
    }
    return {true, L"已开始连接。"};
}

Result Worker::stop() {
    std::lock_guard lock(mutex_);
    if (!running_) return {false, L"当前没有活动的认证会话。"};
    state_.phase = ConnectionPhase::stopping;
    state_.status = L"正在停止认证会话。";
    thread_.request_stop();
    return {true, state_.status};
}

void Worker::publish(ConnectionPhase phase, const wchar_t* message) {
    std::lock_guard lock(mutex_);
    if (state_.phase == ConnectionPhase::stopping) return;
    state_.phase = phase;
    state_.status = message;
    if (state_.logs.size() == 32) state_.logs.erase(state_.logs.begin());
    state_.logs.emplace_back(message);
}

void Worker::run(std::stop_token stop, QString clientId, QString mac, const QString& username,
                 const std::function<QString()>& readPassword) {
    const wchar_t* status = L"校园网认证未完成。";
    bool logoutFailed = false;
    try {
        if (stop.stop_requested()) throw Error("HTTP_CANCELLED");
        const auto portal = dependencies_.discover(stop);
        if (stop.stop_requested()) throw Error("HTTP_CANCELLED");
        auto transport = dependencies_.transport(stop);
        auto cleanup = dependencies_.transport({});
        if (!transport || !cleanup) throw Error("HTTP_TRANSPORT_ERROR");
        Session session({portal.ticketUrl, portal.loginUrl, std::move(clientId), portal.userIpv4,
                         std::move(mac), dependencies_.channel},
            *transport, dependencies_.cipher, [] {
                return QDateTime::currentDateTimeUtc().addSecs(8 * 3600).toString("yyyy-MM-dd HH:mm:ss");
            });
        try {
            status = L"原生会话初始化或取票失败。";
            session.initialize();
            if (stop.stop_requested()) throw Error("HTTP_CANCELLED");
            {
                status = L"校园网登录请求未完成。";
                const Password password{readPassword()};
                session.login(username, password.value);
            }
            if (stop.stop_requested()) throw Error("HTTP_CANCELLED");
            status = L"校园网登录未通过首次心跳确认。";
            if (dependencies_.channel == Channel::android2082 && !wait(stop, 2s))
                throw Error("HTTP_CANCELLED");
            auto delay = session.heartbeat();
            publish(ConnectionPhase::online, L"校园网认证已通过首次心跳确认。");
            while (wait(stop, delay)) {
                try {
                    delay = session.heartbeat();
                } catch (const Error&) {
                    if (stop.stop_requested()) break;
                    publish(ConnectionPhase::recovering, L"心跳失败，正在尝试恢复会话。");
                    if (!wait(stop, 2s)) break;
                    status = L"心跳恢复失败，请重新连接。";
                    delay = session.heartbeat();
                    status = L"校园网认证未完成。";
                    publish(ConnectionPhase::online, L"校园网会话已恢复。");
                }
            }
        } catch (...) {
            try { session.close(cleanup.get()); } catch (...) { logoutFailed = true; }
            throw;
        }
        try { session.close(cleanup.get()); } catch (...) { logoutFailed = true; }
    } catch (const Error& error) {
        const std::string_view code(error.what());
        if (code == "PORTAL_NETWORK_ALREADY_ONLINE") status = L"网络已可访问，当前客户端未建立认证会话。";
        else if (code.starts_with("PORTAL_")) status = L"未识别到有效的校园网认证门户。";
        else if (code == "BOOTSTRAP_MODULE_UNSUPPORTED") status = L"当前校园网模块不受原生认证支持。";
        else if (code == "CREDENTIAL_READ_FAILED") status = L"Windows 无法读取该密码，请重新保存账户。";
    } catch (...) {
        status = L"原生认证执行失败。";
    }
    std::lock_guard lock(mutex_);
    if (stop.stop_requested()) status = L"认证会话已停止。";
    if (state_.logs.size() == 32) state_.logs.erase(state_.logs.begin());
    state_.logs.emplace_back(logoutFailed ? L"本地会话已结束，服务器注销未确认。" : status);
    state_.status = state_.logs.back();
    state_.phase = ConnectionPhase::offline;
    running_ = false;
}
}
