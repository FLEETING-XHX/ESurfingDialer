#include "LocalClientServices.h"

#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTemporaryDir>
#include <QtTest>
#include <atomic>
#include <windows.h>
#include <wincrypt.h>

using esurfing::LocalClientServices;
using esurfing::Settings;
using esurfing::ConnectionPhase;
using namespace esurfing::auth2104;

namespace {
QByteArray contents(const QString& path) {
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) return {};
    return file.readAll();
}

QByteArray storedCredential(const QString& path) {
    const auto root = QJsonDocument::fromJson(contents(path)).object();
    const auto accounts = root.value("accounts").toArray();
    return accounts.isEmpty() ? QByteArray{} :
        QByteArray::fromBase64(accounts.first().toObject().value("credential").toString().toLatin1());
}

class TestCipher final : public Cipher {
    QByteArray algorithmId() const override { return "aa15ab12-1234-5678-9876-123456789011"; }
    QByteArray encrypt(QByteArrayView input) override { return input.toByteArray().toBase64(); }
    QByteArray decrypt(QByteArrayView input) override { return QByteArray::fromBase64(input.toByteArray()); }
};

class TestTransport final : public Transport {
public:
    TestTransport(std::stop_token stop, std::atomic<bool>& login, std::atomic<int>& logout,
                  std::atomic<int>& heartbeats)
        : stop_(stop), login_(login), logout_(logout), heartbeats_(heartbeats) {}

    Response post(const Request& request) override {
        if (stop_.stop_requested()) throw Error("HTTP_CANCELLED");
        const auto path = request.url.path();
        if (path == u"/ticket.cgi" && request.body == "00000000-0000-0000-0000-000000000000")
            return {200, "synthetic-module"};
        if (path == u"/ticket.cgi") return xml("<ticket>fixture</ticket>");
        if (path == u"/auth.cgi") {
            const auto plain = QByteArray::fromBase64(request.body);
            login_ = plain.contains("<userid>synthetic-user</userid>") &&
                plain.contains("<passwd>synthetic-local-password</passwd>");
            if (!login_) return {403, {}};
            return xml("<keep-url>https://campus.test/keep</keep-url>"
                       "<term-url>https://campus.test/term</term-url><keep-retry>60</keep-retry>");
        }
        if (path == u"/keep") {
            const auto beat = ++heartbeats_;
            if (beat == 2) return {500, {}};
            return xml(beat == 1 ? "<interval>5</interval>" : "<interval>60</interval>");
        }
        if (path == u"/term" && !stop_.stop_possible()) { ++logout_; return {200, {}}; }
        return {500, {}};
    }

private:
    static Response xml(const QByteArray& fields) { return {200, ("<response>" + fields + "</response>").toBase64()}; }
    std::stop_token stop_;
    std::atomic<bool>& login_;
    std::atomic<int>& logout_;
    std::atomic<int>& heartbeats_;
};
}

class LocalServiceTests : public QObject {
    Q_OBJECT
private slots:
    void accountAndSettingsSurviveRestart();
    void corruptFileIsPreserved();
    void secondInstanceCannotWrite();
    void failedWriteDoesNotChangeSnapshot();
    void savedAccountRunsNativeWorker();
    void cancelDuringDiscovery();
};

void LocalServiceTests::accountAndSettingsSurviveRestart() {
    QTemporaryDir directory;
    QVERIFY(directory.isValid());
    const auto path = directory.filePath("client-state.json");
    const std::wstring secret = L"synthetic-local-password";
    std::wstring firstId, secondId;
    QByteArray encrypted;
    {
        LocalClientServices service(directory.path());
        const auto result = service.saveAccount({L"", L"合成账户", L"synthetic-user", secret});
        QVERIFY2(result.ok, qPrintable(QString::fromStdWString(result.message)));
        firstId = service.snapshot().selectedId;
        encrypted = storedCredential(path);
        QVERIFY(!encrypted.isEmpty());
        QVERIFY(!contents(path).contains("synthetic-local-password"));
        DATA_BLOB blob{static_cast<DWORD>(encrypted.size()), reinterpret_cast<BYTE*>(encrypted.data())};
        DATA_BLOB plain{};
        QVERIFY(CryptUnprotectData(&blob, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &plain));
        const QByteArray actual(reinterpret_cast<const char*>(plain.pbData), plain.cbData);
        SecureZeroMemory(plain.pbData, plain.cbData);
        LocalFree(plain.pbData);
        QCOMPARE(actual, QByteArray(reinterpret_cast<const char*>(secret.data()), secret.size() * sizeof(wchar_t)));
        QVERIFY(service.saveAccount({firstId, L"新备注", L"synthetic-user", L""}).ok);
        QCOMPARE(storedCredential(path), encrypted);
        QVERIFY(!service.saveAccount({firstId, L"", L"different-user", L""}).ok);
        QVERIFY(service.saveAccount({L"", L"", L"other-test-user", L""}).ok);
        secondId = service.snapshot().accounts.back().id;
        QVERIFY(service.selectAccount(secondId).ok);
        Settings settings;
        settings.dailyReauth = true;
        settings.endHour = 24;
        QVERIFY(service.saveSettings(settings).ok);
    }
    {
        LocalClientServices reopened(directory.path());
        const auto state = reopened.snapshot();
        QCOMPARE(state.accounts.size(), size_t(2));
        QVERIFY(state.accounts.front().label == L"新备注");
        QVERIFY(state.selectedId == secondId);
        QVERIFY(state.settings.dailyReauth);
        QCOMPARE(state.settings.endHour, 24);
        QVERIFY(!reopened.connect().ok);
        QVERIFY(reopened.deleteAccount(firstId).ok);
        QVERIFY(!contents(path).contains(encrypted.toBase64()));
        QVERIFY(reopened.saveAccount({L"", L"", L"third-test-user", L""}).ok);
        QVERIFY(reopened.snapshot().accounts.back().id != secondId);
    }
}

void LocalServiceTests::corruptFileIsPreserved() {
    QTemporaryDir directory;
    QVERIFY(directory.isValid());
    const auto path = directory.filePath("client-state.json");
    const QByteArray corrupt = R"({"schema":99})";
    QFile file(path);
    QVERIFY(file.open(QIODevice::WriteOnly));
    QCOMPARE(file.write(corrupt), corrupt.size());
    file.close();
    LocalClientServices service(directory.path());
    QVERIFY(!service.saveSettings({}).ok);
    QCOMPARE(contents(path), corrupt);
}

void LocalServiceTests::secondInstanceCannotWrite() {
    QTemporaryDir directory;
    QVERIFY(directory.isValid());
    LocalClientServices first(directory.path());
    LocalClientServices second(directory.path());
    QVERIFY(first.saveSettings({}).ok);
    QVERIFY(!second.saveSettings({}).ok);
}

void LocalServiceTests::failedWriteDoesNotChangeSnapshot() {
    QTemporaryDir directory;
    QVERIFY(directory.isValid());
    LocalClientServices service(directory.path());
    QVERIFY(QDir(directory.path()).mkdir("client-state.json"));
    const auto before = service.snapshot();
    QVERIFY(!service.saveAccount({L"", L"", L"synthetic-user", L""}).ok);
    QVERIFY(service.snapshot() == before);
}

void LocalServiceTests::savedAccountRunsNativeWorker() {
    QTemporaryDir directory;
    QVERIFY(directory.isValid());
    {
        LocalClientServices service(directory.path());
        QVERIFY(service.saveAccount({L"", L"", L"synthetic-user", L"synthetic-local-password"}).ok);
    }
    std::atomic<bool> login = false;
    std::atomic<int> logout = 0;
    std::atomic<int> heartbeats = 0;
    LocalClientServices service(directory.path(), {
        [](QByteArrayView module) -> std::unique_ptr<Cipher> {
            if (module != "synthetic-module") return {};
            return std::make_unique<TestCipher>();
        },
        [](std::stop_token) {
            return PortalEndpoints{QUrl("https://campus.test/ticket.cgi"), QUrl("https://campus.test/auth.cgi"), "10.0.0.2"};
        },
        [&](std::stop_token stop) { return std::make_unique<TestTransport>(stop, login, logout, heartbeats); }
    });
    QVERIFY(service.connect().ok);
    QTRY_COMPARE(service.snapshot().phase, ConnectionPhase::online);
    QVERIFY(login.load());
    QVERIFY(!service.connect().ok);
    QTRY_COMPARE_WITH_TIMEOUT(service.snapshot().phase, ConnectionPhase::recovering, 6500);
    QTRY_COMPARE(service.snapshot().phase, ConnectionPhase::online);
    QCOMPARE(heartbeats.load(), 3);
    QVERIFY(service.disconnect().ok);
    QTRY_COMPARE(service.snapshot().phase, ConnectionPhase::offline);
    QCOMPARE(logout.load(), 1);
    const auto path = directory.filePath("device-state.json");
    const auto identity = contents(path);
    QVERIFY(!identity.isEmpty());
    QVERIFY(service.connect().ok);
    QTRY_COMPARE(service.snapshot().phase, ConnectionPhase::online);
    QVERIFY(service.disconnect().ok);
    QTRY_COMPARE(service.snapshot().phase, ConnectionPhase::offline);
    QCOMPARE(logout.load(), 2);
    QCOMPARE(contents(path), identity);
}

void LocalServiceTests::cancelDuringDiscovery() {
    QTemporaryDir directory;
    QVERIFY(directory.isValid());
    std::atomic<bool> discovering = false;
    std::atomic<int> transports = 0;
    LocalClientServices service(directory.path(), {
        [](QByteArrayView) { return std::make_unique<TestCipher>(); },
        [&](std::stop_token stop) -> PortalEndpoints {
            discovering = true;
            while (!stop.stop_requested()) std::this_thread::sleep_for(std::chrono::milliseconds(2));
            return {};
        },
        [&](std::stop_token) -> std::unique_ptr<Transport> { ++transports; return {}; }
    });
    QVERIFY(service.saveAccount({L"", L"", L"synthetic-user", L"synthetic-local-password"}).ok);
    QVERIFY(service.connect().ok);
    QTRY_VERIFY(discovering.load());
    QVERIFY(service.disconnect().ok);
    QTRY_COMPARE(service.snapshot().phase, ConnectionPhase::offline);
    QCOMPARE(transports.load(), 0);
}

QTEST_GUILESS_MAIN(LocalServiceTests)
#include "LocalServiceTests.moc"
