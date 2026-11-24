#include "AuthSession2104.h"
#include "AuthHttpTransport.h"
#include "CampusPortal.h"
#include "Native2082Cipher.h"
#include <atomic>
#include <thread>

#include <QCryptographicHash>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimer>
#include <QtTest>

using namespace esurfing::auth2104;
using namespace std::chrono_literals;

namespace {
constexpr auto zeroAlgorithm = "00000000-0000-0000-0000-000000000000";
constexpr auto fixtureAlgorithm = "12345678-abcd-1234-abcd-123456789abc";
constexpr auto module = "synthetic-2104-module";
constexpr auto loginResponse = "<response><keep-url>https://campus.test/keep</keep-url>"
    "<term-url>https://campus.test/term</term-url><keep-retry>240</keep-retry></response>";

class FixtureCipher final : public Cipher {
public:
    QByteArray algorithmId() const override { return fixtureAlgorithm; }
    QByteArray encrypt(QByteArrayView plaintext) override { return plaintext.toByteArray().toBase64(); }
    QByteArray decrypt(QByteArrayView ciphertext) override {
        const auto result = QByteArray::fromBase64Encoding(ciphertext.toByteArray(),
                                                           QByteArray::AbortOnBase64DecodingErrors);
        if (!result) throw Error("FIXTURE_CIPHERTEXT_INVALID");
        return result.decoded;
    }
};

std::unique_ptr<Cipher> openFixture(QByteArrayView bytes) {
    if (bytes != QByteArrayView(module)) return nullptr;
    return std::make_unique<FixtureCipher>();
}

struct FixtureTransport final : Transport {
    QList<Request> requests;
    QList<Response> responses;
    bool valid = true;
    Channel channel = Channel::windows2104;

    Response post(const Request& request) override {
        requests.append(request);
        const auto checksum = QCryptographicHash::hash(request.body, QCryptographicHash::Md5).toHex();
        valid = valid && request.headers.value("CDC-Checksum") == checksum &&
            request.headers.value("User-Agent") == agentFor(channel) &&
            request.headers.value("Client-ID") == "synthetic-id" &&
            request.responseLimit == 1024 * 1024;
        if (responses.isEmpty()) throw Error("UNEXPECTED_TEST_REQUEST");
        return responses.takeFirst();
    }

    void bootstrap() {
        responses.append(Response{200, module});
        reply("<response><ticket>test-ticket</ticket></response>");
    }

    void reply(const QByteArray& xml) { responses.append(Response{200, xml.toBase64()}); }
};

Config config() {
    return {QUrl("https://campus.test/ticket"), QUrl("https://campus.test/auth"),
            "synthetic-id", "192.0.2.10", "02:00:00:00:00:01"};
}

QString clockValue() { return "2026-11-23 10:20:30"; }
}

class AuthSessionTests : public QObject {
    Q_OBJECT
private slots:
    void complete2104Flow();
    void native2082RejectsInvalidModule();
    void native2082WithCapturedModule();
    void missingProviderIsOffline();
    void moduleOnlyProbe();
    void unknownModuleStopsBeforeCredentials();
    void badTicket_data();
    void badTicket();
    void invalidLogoutTargetFailsClosed();
    void firstHeartbeatAndLogoutFailure();
    void windowsHttpTransport();
    void backgroundHttpDiscoveryTransport();
    void portalDiscovery();
};

void AuthSessionTests::complete2104Flow() {
    FixtureTransport wire;
    wire.bootstrap();
    wire.reply(loginResponse);
    wire.reply("<response><interval>1</interval></response>");
    wire.reply("<response><interval>900</interval></response>");
    wire.responses.append(Response{204, {}});
    int timestampCalls = 0;
    Session session(config(), wire, openFixture, [&] {
        ++timestampCalls;
        return clockValue();
    });

    session.initialize();
    QCOMPARE(session.phase(), Phase::ticketReady);
    session.login("demo<&", "sample<&");
    QCOMPARE(session.phase(), Phase::confirming);
    QCOMPARE(session.heartbeat().count(), 5);
    QCOMPARE(session.phase(), Phase::online);
    QCOMPARE(session.heartbeat().count(), 240);
    QVERIFY(session.close());
    QCOMPARE(session.phase(), Phase::closed);
    QVERIFY(!session.close());

    QVERIFY(wire.valid);
    QVERIFY(wire.responses.isEmpty());
    QCOMPARE(wire.requests.size(), 6);
    QCOMPARE(timestampCalls, 5);
    QCOMPARE(wire.requests[0].body, QByteArray(zeroAlgorithm));
    QCOMPARE(wire.requests[0].headers.value("Algo-ID"), QByteArray(zeroAlgorithm));
    for (qsizetype n = 1; n < wire.requests.size(); ++n)
        QCOMPARE(wire.requests[n].headers.value("Algo-ID"), QByteArray(fixtureAlgorithm));
    const auto ticket = QByteArray::fromBase64(wire.requests[1].body);
    const auto login = QByteArray::fromBase64(wire.requests[2].body);
    const auto keep = QByteArray::fromBase64(wire.requests[3].body);
    QVERIFY(ticket.contains("<ipv4>192.0.2.10</ipv4>"));
    QVERIFY(!ticket.contains("<ticket>"));
    QVERIFY(login.contains("<userid>demo&lt;&amp;</userid>"));
    QVERIFY(login.contains("<passwd>sample&lt;&amp;</passwd>"));
    QVERIFY(keep.contains("<ticket>test-ticket</ticket>"));
    QVERIFY(!keep.contains("<passwd>"));
    QCOMPARE(wire.requests.last().url.path(), QString("/term"));
    QCOMPARE(wire.requests.last().timeout.count(), 3);
}

void AuthSessionTests::native2082RejectsInvalidModule() {
    const auto factory = native2082CipherFactory();
    QVERIFY(factory);
    FixtureTransport wire;
    wire.channel = Channel::android2082;
    wire.responses.append(Response{200, "not a native module"});
    auto cfg = config();
    cfg.channel = wire.channel;
    Session session(cfg, wire, factory, clockValue);
    QVERIFY_THROWS_EXCEPTION(Error, session.initialize());
    QCOMPARE(wire.requests.size(), 1);
    QVERIFY(wire.valid);
}

void AuthSessionTests::native2082WithCapturedModule() {
    const auto path = qEnvironmentVariable("ESURFING_TEST_2082_MODULE");
    if (path.isEmpty()) QSKIP("Private captured module unavailable");
    QFile file(path);
    QVERIFY(file.open(QIODevice::ReadOnly));
    const auto moduleBytes = file.read(1024 * 1024 + 1);
    QVERIFY(moduleBytes.size() <= 1024 * 1024);
    const auto factory = native2082CipherFactory();
    QVERIFY(factory);
    auto reference = factory(moduleBytes);
    QVERIFY(reference);
    auto invalid = moduleBytes.first(10);
    QVERIFY_THROWS_EXCEPTION(Error, factory(invalid));

    const auto vectorPath = qEnvironmentVariable("ESURFING_TEST_2082_VECTORS");
    if (!vectorPath.isEmpty()) {
        QFile vectors(vectorPath);
        QVERIFY(vectors.open(QIODevice::ReadOnly));
        QVERIFY(vectors.size() <= 1024 * 1024);
        QCOMPARE(vectors.readLine().trimmed(), QByteArray("sequence\tinput_utf8_hex\tnative_enc_utf8_hex"));
        int count = 0;
        while (!vectors.atEnd()) {
            const auto fields = vectors.readLine().trimmed().split('\t');
            QCOMPARE(fields.size(), 3);
            const auto ciphertext = QByteArray::fromHex(fields.last());
            const auto plaintext = reference->decrypt(ciphertext);
            QVERIFY(reference->encrypt(plaintext) == ciphertext);
            if (++count == 11) {
                QVERIFY(plaintext.contains("<client-id>11111111-1111-4111-8111-111111111111</client-id>"));
                QVERIFY(plaintext.contains("&amp; &lt;test&gt;"));
            }
        }
        QCOMPARE(count, 11);
    }

    FixtureTransport wire;
    wire.channel = Channel::android2082;
    wire.responses.append(Response{200, moduleBytes});
    wire.responses.append(Response{200, reference->encrypt("<response><ticket>test-ticket</ticket></response>")});
    wire.responses.append(Response{200, reference->encrypt(loginResponse)});
    wire.responses.append(Response{200, reference->encrypt("<response><interval>240</interval></response>")});
    wire.responses.append(Response{204, {}});
    auto cfg = config();
    cfg.channel = wire.channel;
    Session session(cfg, wire, factory, clockValue);
    session.initialize();
    session.login("demo<&'", "sample");
    QCOMPARE(session.heartbeat().count(), 240);
    QVERIFY(session.close());
    QVERIFY(wire.valid);
    QCOMPARE(wire.requests.size(), 5);
    for (qsizetype n = 1; n < wire.requests.size(); ++n)
        QCOMPARE(wire.requests[n].headers.value("Algo-ID"), reference->algorithmId());
    const auto login = reference->decrypt(wire.requests[2].body);
    QVERIFY(login == "<?xml version=\"1.0\" encoding=\"utf-8\"?><request>\n"
        "    <user-agent>CCTP/Android8_vpn/2082</user-agent>\n"
        "    <client-id>synthetic-id</client-id>\n"
        "    <local-time>2026-11-23 10:20:30</local-time>\n"
        "    <ticket>test-ticket</ticket>\n"
        "    <userid>demo&lt;&amp;'</userid>\n"
        "    <passwd>sample</passwd>\n"
        "</request>");
}

void AuthSessionTests::missingProviderIsOffline() {
    FixtureTransport wire;
    Session session(config(), wire, {}, clockValue);
    QVERIFY_THROWS_EXCEPTION(Error, session.initialize());
    QCOMPARE(session.phase(), Phase::failed);
    QVERIFY(wire.requests.isEmpty());
}

void AuthSessionTests::unknownModuleStopsBeforeCredentials() {
    FixtureTransport wire;
    wire.responses.append(Response{200, "unrecognized module"});
    Session session(config(), wire, openFixture, clockValue);
    QVERIFY_THROWS_EXCEPTION(Error, session.initialize());
    QVERIFY_THROWS_EXCEPTION(Error, session.login("demo", "sample"));
    QCOMPARE(wire.requests.size(), 1);
}

void AuthSessionTests::badTicket_data() {
    QTest::addColumn<QByteArray>("xml");
    QTest::newRow("duplicate") << QByteArray("<r><ticket>a</ticket><ticket>b</ticket></r>");
    QTest::newRow("nested") << QByteArray("<r><x><ticket>a</ticket></x></r>");
    QTest::newRow("DTD") << QByteArray("<!DOCTYPE r [<!ENTITY x 'a'>]><r><ticket>&x;</ticket></r>");
    QTest::newRow("trailing root") << QByteArray("<r><ticket>text</ticket></r><r/>");
}

void AuthSessionTests::badTicket() {
    QFETCH(QByteArray, xml);
    FixtureTransport wire;
    wire.responses.append(Response{200, module});
    wire.reply(xml);
    Session session(config(), wire, openFixture, clockValue);
    QVERIFY_THROWS_EXCEPTION(Error, session.initialize());
    QCOMPARE(session.phase(), Phase::failed);
}

void AuthSessionTests::invalidLogoutTargetFailsClosed() {
    FixtureTransport wire;
    wire.bootstrap();
    wire.reply("<response><keep-url>https://campus.test/keep</keep-url>"
               "<term-url>http://127.0.0.1/term</term-url><keep-retry>240</keep-retry></response>");
    Session session(config(), wire, openFixture, clockValue);
    session.initialize();
    QVERIFY_THROWS_EXCEPTION(Error, session.login("demo", "sample"));
    QCOMPARE(session.phase(), Phase::failed);
    QVERIFY(!session.close());
    QCOMPARE(wire.requests.size(), 3);
}

void AuthSessionTests::firstHeartbeatAndLogoutFailure() {
    FixtureTransport wire;
    wire.bootstrap();
    wire.reply(loginResponse);
    wire.responses.append(Response{302, {}});
    wire.responses.append(Response{500, {}});
    Session session(config(), wire, openFixture, clockValue);
    session.initialize();
    session.login("demo", "sample");
    QVERIFY_THROWS_EXCEPTION(Error, session.heartbeat());
    QCOMPARE(session.phase(), Phase::confirming);
    QVERIFY_THROWS_EXCEPTION(Error, session.close());
    QCOMPARE(session.phase(), Phase::closed);
    QVERIFY(!session.close());
    QCOMPARE(wire.requests.size(), 5);
}

void AuthSessionTests::windowsHttpTransport() {
    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost));
    const auto url = QUrl(QString("http://127.0.0.1:%1/auth").arg(server.serverPort()));
    int requests = 0;
    QByteArray response;
    connect(&server, &QTcpServer::newConnection, &server, [&] {
        auto* socket = server.nextPendingConnection();
        connect(socket, &QTcpSocket::readyRead, socket, [&, socket, received = QByteArray{}, handled = false]() mutable {
            received.append(socket->readAll());
            if (handled || !received.contains("\r\n\r\n")) return;
            handled = true;
            ++requests;
            if (response.isEmpty()) return;
            socket->write(response);
            socket->disconnectFromHost();
        });
    });
    HttpTransport http;
    response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok";
    Request request{url, {}, "test-body", 12, 1s};
    QCOMPARE(http.post(request).body, QByteArray("ok"));

    response = "HTTP/1.1 302 Found\r\nLocation: /another-path\r\n"
               "Content-Length: 0\r\nConnection: close\r\n\r\n";
    QCOMPARE(http.post(request).status, 302);
    QCOMPARE(requests, 2);

    response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
               "10\r\n0123456789abcdef\r\n0\r\n\r\n";
    try {
        http.post(request);
        QFAIL("Expected response size limit");
    } catch (const Error& error) {
        QCOMPARE(QByteArray(error.what()), QByteArray("HTTP_BODY_TOO_LARGE"));
    }
    QCOMPARE(requests, 3);

    response.clear();
    try {
        http.post(request);
        QFAIL("Expected response deadline");
    } catch (const Error& error) {
        QCOMPARE(QByteArray(error.what()), QByteArray("HTTP_TIMEOUT"));
    }
    QCOMPARE(requests, 4);
    std::stop_source source;
    HttpTransport cancelled(source.get_token());
    QTimer::singleShot(100, &server, [&] { source.request_stop(); });
    try {
        cancelled.post(request);
        QFAIL("Expected in-flight cancellation");
    } catch (const Error& error) {
        QCOMPARE(QByteArray(error.what()), QByteArray("HTTP_CANCELLED"));
    }
}

void AuthSessionTests::backgroundHttpDiscoveryTransport() {
    QTcpServer server;
    QVERIFY(server.listen(QHostAddress::LocalHost));
    const auto url = QUrl(QString("http://127.0.0.1:%1/probe").arg(server.serverPort()));
    bool gotGet = false;
    connect(&server, &QTcpServer::newConnection, &server, [&] {
        auto* socket = server.nextPendingConnection();
        connect(socket, &QTcpSocket::readyRead, socket, [&, socket, data = QByteArray{}]() mutable {
            data.append(socket->readAll());
            if (!data.contains("\r\n\r\n")) return;
            gotGet = data.startsWith("GET /probe HTTP/1.1\r\n");
            socket->write("HTTP/1.1 302 Found\r\nLocation: /portal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            socket->disconnectFromHost();
        });
    });
    std::atomic<bool> received = false;
    std::jthread worker([&](std::stop_token stop) {
        try {
            HttpTransport http(stop);
            const auto response = http.get(Request{url, {}, {}, 1024, 2s});
            received = response.status == 302 && response.redirect == QUrl("/portal");
        } catch (const Error&) {}
    });
    QTRY_VERIFY(received.load());
    QVERIFY(gotGet);
}

void AuthSessionTests::moduleOnlyProbe() {
    FixtureTransport wire;
    const auto fixture = config();
    wire.bootstrap();
    QVERIFY(!fetchBootstrap(fixture.ticketUrl, fixture.clientId, wire).isEmpty());
    QCOMPARE(wire.requests.size(), 1);
    QCOMPARE(wire.requests.first().headers.value("Algo-ID"), QByteArray("00000000-0000-0000-0000-000000000000"));
    QVERIFY_THROWS_EXCEPTION(Error, fetchBootstrap(fixture.ticketUrl, "invalid\nheader", wire));
    QCOMPARE(wire.requests.size(), 1);
}

void AuthSessionTests::portalDiscovery() {
    const QByteArray portal = "header<!--//config.campus.js.chinatelecom.com\n"
        "\xef\xbb\xbf<?xml version=\"1.0\"?>"
        "<config data='a>b&key=value'>"
        "<auth-url>https://CAMPUS.test:443/auth.cgi</auth-url>"
        "<nested><auth-url><![CDATA[https://campus.test/auth.cgi]]></auth-url></nested>"
        "<ticket-url>https://campus.test/ticket.cgi?wlanuserip=10.0.0.8&wlanacip=10.0.0.1</ticket-url>"
        "</config>\n//config.campus.js.chinatelecom.com-->footer";
    int requests = 0;
    const QUrl probe("http://probe.test/check");
    const auto result = discoverPortal(probe, [&](const Request& request) -> Response {
        if (request.headers.value("User-Agent") != userAgent) throw Error("TEST_HEADER_INVALID");
        if (++requests == 1 && request.url == probe) return {302, {}, QUrl("/portal")};
        if (requests == 2 && request.url == QUrl("http://probe.test/portal")) return {200, portal};
        throw Error("TEST_URL_INVALID");
    });
    QCOMPARE(requests, 2);
    QCOMPARE(result.loginUrl, QUrl("https://campus.test/auth.cgi"));
    QCOMPARE(result.userIpv4, QString("10.0.0.8"));
    QVERIFY(!parsePortal("plain html"));
    QVERIFY_THROWS_EXCEPTION(Error, parsePortal(portal + portal));
    auto invalid = portal;
    invalid.replace("wlanacip=10.0.0.1", "wlanacip=10.0.0.1&wlanacip=10.0.0.2");
    QVERIFY_THROWS_EXCEPTION(Error, parsePortal(invalid));
    requests = 0;
    const auto badRedirect = [&](const Request&) -> Response {
        ++requests;
        return {302, {}, QUrl("http://localhost./portal")};
    };
    QVERIFY_THROWS_EXCEPTION(Error, discoverPortal(probe, badRedirect));
    QCOMPARE(requests, 1);
    QVERIFY_THROWS_EXCEPTION(Error, discoverPortal(probe, [](const Request&) -> Response { return {204, {}}; }));
}

QTEST_GUILESS_MAIN(AuthSessionTests)
#include "AuthSessionTests.moc"
