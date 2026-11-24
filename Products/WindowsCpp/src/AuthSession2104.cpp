#include "AuthSession2104.h"

#include <QCryptographicHash>
#include <QHostAddress>
#include <QRegularExpression>
#include <QSet>
#include <QXmlStreamReader>
#include <QXmlStreamWriter>
#include <algorithm>
#include <utility>
#include <windows.h>

using namespace std::chrono_literals;

namespace esurfing::auth2104 {
bool allowedUrl(const QUrl& url) {
    const auto host = url.host().toLower();
    const QHostAddress ip(host);
    return url.isValid() && (url.scheme() == "http" || url.scheme() == "https") &&
        !host.isEmpty() && !url.authority().contains('@') && !url.hasFragment() &&
        url.toEncoded().size() <= 8192 && host != "localhost" && host != "localhost." &&
        !host.endsWith(".localhost") && !host.endsWith(".localhost.") &&
        !ip.isLoopback() && !ip.isLinkLocal() && ip != QHostAddress::AnyIPv4 && ip != QHostAddress::AnyIPv6;
}

bool validIpv4(const QString& address) {
    const auto parts = address.split('.');
    return parts.size() == 4 && std::all_of(parts.begin(), parts.end(), [](const QString& part) {
        return !part.isEmpty() && part.size() <= 3 && part.toInt() <= 255 &&
            std::all_of(part.begin(), part.end(), [](QChar ch) { return ch >= u'0' && ch <= u'9'; });
    });
}

namespace {
constexpr qsizetype maxBody = 1024 * 1024;
constexpr qsizetype maxXml = 64 * 1024;
constexpr auto accept = "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*";
constexpr auto bootstrapId = "00000000-0000-0000-0000-000000000000";

bool validClientId(QByteArrayView id) {
    return !id.isEmpty() && id.size() <= 128 && std::all_of(id.begin(), id.end(),
        [](unsigned char c) { return c >= 0x21 && c <= 0x7e; });
}

bool validAlgorithm(const QByteArray& id) {
    static const QRegularExpression uuid("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$",
                                         QRegularExpression::DontCaptureOption);
    return id.size() == 36 && uuid.match(QString::fromLatin1(id)).hasMatch();
}

QMap<QString, QString> responseFields(const QByteArray& xml, std::initializer_list<QString> required) {
    if (xml.isEmpty() || xml.size() > maxXml) throw Error("RESPONSE_XML_SIZE");
    QXmlStreamReader reader(xml);
    QMap<QString, QString> fields;
    const QSet<QString> names(required);
    int depth = 0;
    bool foundRoot = false;
    while (!reader.atEnd()) {
        switch (reader.readNext()) {
        case QXmlStreamReader::DTD:
        case QXmlStreamReader::EntityReference:
            throw Error("RESPONSE_XML_INVALID");
        case QXmlStreamReader::StartElement: {
            foundRoot = true;
            ++depth;
            const auto name = reader.qualifiedName().toString();
            if (!names.contains(name)) break;
            if (depth != 2 || fields.contains(name)) throw Error("RESPONSE_FIELDS_INVALID");
            const auto value = reader.readElementText(QXmlStreamReader::ErrorOnUnexpectedElement).trimmed();
            if (reader.hasError() || value.isEmpty()) throw Error("RESPONSE_FIELDS_INVALID");
            fields.insert(name, value);
            --depth;
            break;
        }
        case QXmlStreamReader::EndElement:
            --depth;
            break;
        default:
            break;
        }
    }
    if (reader.hasError() || !foundRoot || depth != 0) throw Error("RESPONSE_XML_INVALID");
    if (fields.size() != names.size()) throw Error("RESPONSE_FIELDS_INVALID");
    return fields;
}

std::chrono::seconds interval(const QString& value) {
    if (!std::all_of(value.begin(), value.end(), [](QChar ch) { return ch >= u'0' && ch <= u'9'; }))
        throw Error("HEARTBEAT_INTERVAL_INVALID");
    bool ok = false;
    const auto seconds = value.toLongLong(&ok);
    if (!ok || seconds <= 0) throw Error("HEARTBEAT_INTERVAL_INVALID");
    return std::chrono::seconds(std::clamp(seconds, 5LL, 240LL));
}

// Own the generated plaintext until encryption finishes or throws.
struct Plaintext {
    QByteArray bytes;
    ~Plaintext() {
        if (!bytes.isEmpty()) SecureZeroMemory(bytes.data(), static_cast<size_t>(bytes.size()));
    }
};

QByteArray requestBody(Transport& transport, const QUrl& url, const QString& clientId,
                       const QByteArray& algorithm, QByteArray body, std::chrono::seconds timeout,
                       Channel channel) {
    if (body.isEmpty() || body.size() > maxBody) throw Error("REQUEST_BODY_SIZE");
    auto checksum = QCryptographicHash::hash(body, QCryptographicHash::Md5).toHex();
    const auto response = transport.post(Request{url, {
        {"User-Agent", agentFor(channel)}, {"Accept", accept},
        {"Content-Type", "application/x-www-form-urlencoded; charset=utf-8"},
        {"CDC-Checksum", std::move(checksum)}, {"Client-ID", clientId.toUtf8()},
        {"Algo-ID", algorithm}}, std::move(body), maxBody, timeout});
    if (response.status < 200 || response.status >= 300) throw Error("AUTH_HTTP_ERROR");
    if (response.body.size() > maxBody) throw Error("RESPONSE_BODY_SIZE");
    return response.body;
}
}

QByteArray fetchBootstrap(const QUrl& ticketUrl, const QString& clientId, Transport& transport, Channel channel) {
    if (!allowedUrl(ticketUrl) || !validClientId(clientId.toUtf8())) throw Error("BOOTSTRAP_CONFIG_INVALID");
    auto module = requestBody(transport, ticketUrl, clientId, bootstrapId, bootstrapId, 45s, channel);
    if (module.isEmpty()) throw Error("BOOTSTRAP_BODY_EMPTY");
    return module;
}

Session::Session(Config config, Transport& transport, CipherFactory createCipher,
                 std::function<QString()> timestamp)
    : config_(std::move(config)), transport_(transport), createCipher_(std::move(createCipher)),
      timestamp_(std::move(timestamp)) {
    if (!allowedUrl(config_.ticketUrl) || !allowedUrl(config_.loginUrl)) throw Error("AUTH_URL_INVALID");
    if (!validClientId(config_.clientId.toUtf8()) || !timestamp_) throw Error("AUTH_CONFIG_INVALID");
    if (!validIpv4(config_.localIpv4)) throw Error("SESSION_IP_INVALID");
}

void Session::require(Phase expected) const {
    if (phase_ != expected) throw Error("SESSION_PHASE_INVALID");
}

QByteArray Session::post(const QUrl& url, QByteArray body, std::chrono::seconds timeout,
                         Transport* overrideTransport) {
    auto& transport = overrideTransport ? *overrideTransport : transport_;
    return requestBody(transport, url, config_.clientId, algorithm_, std::move(body), timeout, config_.channel);
}

QByteArray Session::encrypt(const Fields& fields) {
    Plaintext plaintext;
    if (config_.channel == Channel::android2082) {
        // Match the request serialized by the 2082 native implementation, including its empty tags.
        plaintext.bytes = "<?xml version=\"1.0\" encoding=\"utf-8\"?><request>\n";
        for (const auto& [name, value] : fields) {
            if (!value.isValidUtf16() || std::any_of(value.begin(), value.end(), [](QChar ch) {
                    return (ch.unicode() < 32 && ch != u'\r' && ch != u'\n' && ch != u'\t') ||
                        ch.unicode() == 0xfffe || ch.unicode() == 0xffff;
                }))
                throw Error("REQUEST_XML_INVALID");
            Plaintext field{value.toUtf8()};
            field.bytes.replace("\r\n", "\n").replace('\r', '\n');
            field.bytes.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            const auto tag = name.toLatin1();
            plaintext.bytes.append("    <").append(tag).append('>').append(field.bytes)
                .append("</").append(tag).append(">\n");
        }
        plaintext.bytes += "</request>";
        if (plaintext.bytes.size() > maxXml) throw Error("REQUEST_XML_INVALID");
        return cipher_->encrypt(plaintext.bytes);
    }
    QXmlStreamWriter writer(&plaintext.bytes);
    writer.writeStartDocument();
    writer.writeStartElement("request");
    for (const auto& [name, value] : fields) writer.writeTextElement(name, value);
    writer.writeEndDocument();
    if (writer.hasError() || plaintext.bytes.size() > maxXml) throw Error("REQUEST_XML_INVALID");
    return cipher_->encrypt(plaintext.bytes);
}

QByteArray Session::exchange(const QUrl& url, const Fields& fields) {
    const auto ciphertext = post(url, encrypt(fields), 45s);
    if (ciphertext.isEmpty()) throw Error("AUTH_BODY_EMPTY");
    return cipher_->decrypt(ciphertext);
}

Session::Fields Session::identityFields(bool includeTicket) const {
    Fields fields{{"user-agent", agentFor(config_.channel)}, {"client-id", config_.clientId},
        {"local-time", timestamp_()}, {"host-name", "Xiaomi 6"}, {"ipv4", config_.localIpv4}};
    if (includeTicket) fields.emplaceBack("ticket", ticket_);
    fields.emplaceBack("ipv6", "");
    fields.emplaceBack("mac", config_.mac);
    fields.emplaceBack("ostag", "Xiaomi 6");
    return fields;
}

void Session::initialize() {
    require(Phase::idle);
    phase_ = Phase::failed;
    if (!createCipher_) throw Error("CIPHER_PROVIDER_UNAVAILABLE");
    const auto module = fetchBootstrap(config_.ticketUrl, config_.clientId, transport_, config_.channel);
    cipher_ = createCipher_(module);
    if (!cipher_) throw Error("BOOTSTRAP_MODULE_UNSUPPORTED");
    algorithm_ = cipher_->algorithmId();
    if (!validAlgorithm(algorithm_) || algorithm_ == "00000000-0000-0000-0000-000000000000")
        throw Error("BOOTSTRAP_ALGORITHM_INVALID");
    ticket_ = responseFields(exchange(config_.ticketUrl, identityFields(false)), {"ticket"}).value("ticket");
    phase_ = Phase::ticketReady;
}

void Session::login(const QString& username, const QString& password) {
    require(Phase::ticketReady);
    if (username.isEmpty() || username.size() > 128 || password.isEmpty() || password.size() > 256)
        throw Error("ACCOUNT_INPUT_INVALID");
    phase_ = Phase::failed;
    const auto fields = responseFields(exchange(config_.loginUrl, {{"user-agent", agentFor(config_.channel)},
        {"client-id", config_.clientId}, {"local-time", timestamp_()}, {"ticket", ticket_},
        {"userid", username}, {"passwd", password}}), {"keep-url", "term-url", "keep-retry"});
    const QUrl logout(fields.value("term-url"), QUrl::StrictMode);
    const QUrl heartbeat(fields.value("keep-url"), QUrl::StrictMode);
    if (!allowedUrl(logout) || !allowedUrl(heartbeat)) throw Error("LOGIN_URL_INVALID");
    // Keep the validated cleanup target even if the heartbeat interval prevents going online.
    logoutUrl_ = logout;
    static_cast<void>(interval(fields.value("keep-retry")));
    heartbeatUrl_ = heartbeat;
    phase_ = Phase::confirming;
}

std::chrono::seconds Session::heartbeat() {
    if (phase_ != Phase::confirming && phase_ != Phase::online) throw Error("SESSION_PHASE_INVALID");
    // A failed first heartbeat cannot promote the session to online. An existing session
    // becomes unconfirmed so the owner can decide whether to retry or close.
    phase_ = Phase::confirming;
    const auto seconds = interval(responseFields(exchange(heartbeatUrl_, identityFields(true)),
                                                {"interval"}).value("interval"));
    phase_ = Phase::online;
    return seconds;
}

bool Session::close(Transport* cleanupTransport) {
    if (phase_ == Phase::closed) return false;
    phase_ = Phase::closed;
    try {
        const bool send = cipher_ && !logoutUrl_.isEmpty();
        if (send) static_cast<void>(post(logoutUrl_, encrypt(identityFields(true)), 3s, cleanupTransport));
        cipher_.reset();
        ticket_.clear();
        return send;
    } catch (...) {
        cipher_.reset();
        ticket_.clear();
        throw;
    }
}
}
