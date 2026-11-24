#pragma once

#include <QByteArray>
#include <QMap>
#include <QString>
#include <QUrl>
#include <chrono>
#include <functional>
#include <initializer_list>
#include <memory>
#include <optional>
#include <stdexcept>

namespace esurfing::auth2104 {

inline constexpr auto userAgent = "CCTP/android11_64/2104";
enum class Channel { windows2104, android2082 };
inline constexpr const char* agentFor(Channel channel) {
    return channel == Channel::android2082 ? "CCTP/Android8_vpn/2082" : userAgent;
}
bool allowedUrl(const QUrl& url);
bool validIpv4(const QString& address);

struct Request {
    QUrl url;
    QMap<QByteArray, QByteArray> headers;
    QByteArray body;
    qsizetype responseLimit;
    std::chrono::seconds timeout;
};

struct Response {
    int status;
    QByteArray body;
    QUrl redirect{};
};

class Transport {
public:
    virtual ~Transport() = default;
    // Implementations must honor the byte and time limits and reject redirects.
    virtual Response post(const Request& request) = 0;
};

class Cipher {
public:
    virtual ~Cipher() = default;
    virtual QByteArray algorithmId() const = 0;
    virtual QByteArray encrypt(QByteArrayView plaintext) = 0;
    virtual QByteArray decrypt(QByteArrayView ciphertext) = 0;
};

using CipherFactory = std::function<std::unique_ptr<Cipher>(QByteArrayView module)>;
QByteArray fetchBootstrap(const QUrl& ticketUrl, const QString& clientId, Transport& transport,
                          Channel channel = Channel::windows2104);

struct Config {
    QUrl ticketUrl;
    QUrl loginUrl;
    QString clientId;
    QString localIpv4;
    QString mac;
    Channel channel = Channel::windows2104;
};

class Error : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

enum class Phase { idle, ticketReady, confirming, online, failed, closed };

// One session on its owning worker. Drive periodic heartbeats and retry scheduling externally.
class Session {
public:
    Session(Config config, Transport& transport, CipherFactory createCipher,
            std::function<QString()> timestamp);
    Phase phase() const { return phase_; }
    void initialize();
    void login(const QString& username, const QString& password);
    std::chrono::seconds heartbeat();
    // Returns whether a logout request was sent and accepted by HTTP. The server-side logout
    // has its own confirmation semantics. Local state is released even if transport fails.
    bool close(Transport* cleanupTransport = nullptr);

private:
    using Fields = QList<std::pair<QString, QString>>;
    QByteArray post(const QUrl& url, QByteArray body, std::chrono::seconds timeout,
                    Transport* overrideTransport = nullptr);
    QByteArray encrypt(const Fields& fields);
    QByteArray exchange(const QUrl& url, const Fields& fields);
    Fields identityFields(bool includeTicket) const;
    void require(Phase expected) const;

    Config config_;
    Transport& transport_;
    CipherFactory createCipher_;
    std::function<QString()> timestamp_;
    std::unique_ptr<Cipher> cipher_;
    QByteArray algorithm_ = "00000000-0000-0000-0000-000000000000";
    QString ticket_;
    QUrl heartbeatUrl_;
    QUrl logoutUrl_;
    Phase phase_ = Phase::idle;
};
}
