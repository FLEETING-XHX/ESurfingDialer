#include "LocalClientServices.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSet>
#include <QUuid>
#include <algorithm>
#include <windows.h>
#include <wincrypt.h>

namespace esurfing {
namespace {
constexpr qsizetype maxFileBytes = 1024 * 1024;
constexpr qsizetype maxAccounts = 100;
constexpr qsizetype maxCredentialBytes = 4096;
constexpr int schemaVersion = 1;

QString str(const std::wstring& value) { return QString::fromStdWString(value); }

QByteArray protectPassword(const std::wstring& password) {
    DATA_BLOB input{static_cast<DWORD>(password.size() * sizeof(wchar_t)),
                    reinterpret_cast<BYTE*>(const_cast<wchar_t*>(password.data()))};
    DATA_BLOB output{};
    if (!CryptProtectData(&input, L"ESurfingDialer WindowsCpp", nullptr, nullptr, nullptr,
                          CRYPTPROTECT_UI_FORBIDDEN, &output))
        return {};
    QByteArray encrypted(reinterpret_cast<const char*>(output.pbData), output.cbData);
    LocalFree(output.pbData);
    return encrypted;
}

QString unprotectPassword(QByteArray& encrypted) {
    DATA_BLOB input{static_cast<DWORD>(encrypted.size()), reinterpret_cast<BYTE*>(encrypted.data())};
    DATA_BLOB output{};
    if (!CryptUnprotectData(&input, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output))
        throw auth2104::Error("CREDENTIAL_READ_FAILED");
    struct FreePassword {
        DATA_BLOB& data;
        ~FreePassword() { SecureZeroMemory(data.pbData, data.cbData); LocalFree(data.pbData); }
    } cleanup{output};
    if (output.cbData == 0 || output.cbData > 256 * sizeof(wchar_t) || output.cbData % sizeof(wchar_t) != 0)
        throw auth2104::Error("CREDENTIAL_READ_FAILED");
    return QString::fromWCharArray(reinterpret_cast<const wchar_t*>(output.pbData), output.cbData / sizeof(wchar_t));
}

std::optional<std::pair<QString, QString>> deviceIdentity(const QString& path) {
    const QFileInfo info(path);
    if (info.isSymLink() || info.isJunction()) return std::nullopt;
    if (info.exists()) {
        QFile file(path);
        if (!info.isFile() || !file.open(QIODevice::ReadOnly)) return std::nullopt;
        const auto bytes = file.read(1025);
        if (file.error() != QFile::NoError || bytes.size() > 1024 || !file.atEnd()) return std::nullopt;
        const auto object = QJsonDocument::fromJson(bytes).object();
        const auto id = object.value("clientId").toString();
        const auto mac = object.value("macAddress").toString();
        static const QRegularExpression macPattern(QStringLiteral("^[0-9a-f]{2}(:[0-9a-f]{2}){5}\\z"));
        if (id.size() != 36 || QUuid(id).isNull() || !macPattern.match(mac).hasMatch()) return std::nullopt;
        return std::pair{id, mac};
    }
    const auto id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    auto macBytes = QUuid::createUuid().toRfc4122().first(6);
    macBytes[0] = static_cast<char>((static_cast<unsigned char>(macBytes[0]) & 0xfc) | 0x02);
    const auto mac = QString::fromLatin1(macBytes.toHex(':'));
    QSaveFile file(path);
    const auto bytes = QJsonDocument(QJsonObject{{"clientId", id}, {"macAddress", mac}}).toJson(QJsonDocument::Compact);
    if (!file.open(QIODevice::WriteOnly) || file.write(bytes) != bytes.size() || !file.commit()) return std::nullopt;
    return std::pair{id, mac};
}

QJsonObject writeSettings(const Settings& s) {
    return {{"autoStart", s.autoStart}, {"startMinimized", s.startMinimized},
            {"autoConnect", s.autoConnect}, {"backgroundNotice", s.backgroundNotice},
            {"dailyReauth", s.dailyReauth}, {"enhancedConnection", s.enhancedConnection},
            {"startHour", s.startHour}, {"endHour", s.endHour}};
}

bool readSettings(const QJsonObject& object, Settings& s) {
    for (auto key : {"autoStart", "startMinimized", "autoConnect", "backgroundNotice",
                     "dailyReauth", "enhancedConnection"}) {
        if (!object.value(key).isBool()) return false;
    }
    s.autoStart = object.value("autoStart").toBool();
    s.startMinimized = object.value("startMinimized").toBool();
    s.autoConnect = object.value("autoConnect").toBool();
    s.backgroundNotice = object.value("backgroundNotice").toBool();
    s.dailyReauth = object.value("dailyReauth").toBool();
    s.enhancedConnection = object.value("enhancedConnection").toBool();
    s.startHour = object.value("startHour").toInt(-1);
    s.endHour = object.value("endHour").toInt(-1);
    return true;
}
}

LocalClientServices::LocalClientServices(const QString& directory, auth2104::Worker::Dependencies authentication)
    : path_(QDir(directory).filePath("client-state.json")),
      lock_(QDir(directory).filePath("client-state.lock")), authentication_(std::move(authentication)) {
    model_.state_.logs = {L"Windows C++ 本地服务已启动。"};
    if (!authentication_.available()) {
        model_.state_.status = L"原生认证组件不可用。";
        model_.state_.logs.push_back(model_.state_.status);
    }
    if (directory.isEmpty() || !QDir().mkpath(directory)) {
        storageError_ = L"无法创建本地配置目录。";
    } else if (!lock_.tryLock()) {
        storageError_ = L"无法独占本地配置，请关闭其他客户端实例后重试。";
    } else if (!load()) {
        storageError_ = L"本地配置无法读取或格式无效，原文件已保留。";
    }
    if (!storageError_.empty()) model_.state_.logs.push_back(storageError_);
}

bool LocalClientServices::load() {
    const QFileInfo info(path_);
    if (info.isSymLink()) return false;
    if (!info.exists()) return true;
    QFile file(path_);
    if (!info.isFile() || !file.open(QIODevice::ReadOnly)) return false;
    const QByteArray bytes = file.read(maxFileBytes + 1);
    if (file.error() != QFile::NoError || bytes.size() > maxFileBytes || !file.atEnd())
        return false;
    const auto document = QJsonDocument::fromJson(bytes);
    if (!document.isObject()) return false;
    const auto root = document.object();
    if (root.value("schema").toInt() != schemaVersion || !root.value("accounts").isArray() ||
        !root.value("selectedId").isString() || !root.value("settings").isObject())
        return false;
    Settings settings;
    PreviewServices candidate = model_;
    if (!readSettings(root.value("settings").toObject(), settings) ||
        !candidate.saveSettings(settings).ok)
        return false;
    const auto accounts = root.value("accounts").toArray();
    if (accounts.size() > maxAccounts) return false;
    QSet<QString> ids;
    Credentials credentials;
    for (const auto& entry : accounts) {
        const auto account = entry.toObject();
        const auto id = account.value("id").toString();
        const auto username = account.value("username").toString();
        const auto label = account.value("label").toString();
        if (id.isEmpty() || id.size() > 64 || ids.contains(id) || username.isEmpty() ||
            username.size() > 128 || !account.value("label").isString() || label.size() > 40)
            return false;
        ids.insert(id);
        if (!account.value("credential").isString()) return false;
        const auto encoded = account.value("credential").toString().toLatin1();
        if (!encoded.isEmpty()) {
            const auto decoded = QByteArray::fromBase64Encoding(encoded, QByteArray::AbortOnBase64DecodingErrors);
            if (!decoded || decoded.decoded.isEmpty() || decoded.decoded.size() > maxCredentialBytes)
                return false;
            credentials.insert(id, decoded.decoded);
        }
        candidate.state_.accounts.push_back({id.toStdWString(), label.toStdWString(), username.toStdWString()});
    }
    const auto selected = root.value("selectedId").toString();
    if (accounts.isEmpty() ? !selected.isEmpty() : !ids.contains(selected)) return false;
    candidate.state_.selectedId = selected.toStdWString();
    model_ = std::move(candidate);
    credentials_ = std::move(credentials);
    return true;
}

bool LocalClientServices::commit(PreviewServices candidate, Credentials credentials) {
    const auto& state = candidate.state_;
    QJsonArray accounts;
    for (const auto& account : state.accounts) {
        const auto id = str(account.id);
        accounts.append(QJsonObject{{"id", id}, {"label", str(account.label)},
            {"username", str(account.username)},
            {"credential", QString::fromLatin1(credentials.value(id).toBase64())}});
    }
    const QJsonDocument document(QJsonObject{{"schema", schemaVersion}, {"accounts", accounts},
        {"selectedId", str(state.selectedId)}, {"settings", writeSettings(state.settings)}});
    const auto bytes = document.toJson(QJsonDocument::Compact);
    QSaveFile file(path_);
    if (bytes.size() > maxFileBytes || QFileInfo(path_).isSymLink() ||
        !file.open(QIODevice::WriteOnly) || file.write(bytes) != bytes.size() || !file.commit())
        return false;
    model_ = std::move(candidate);
    credentials_ = std::move(credentials);
    return true;
}

Snapshot LocalClientServices::snapshot() const {
    auto result = model_.snapshot();
    if (!authentication_.available()) return result;
    const auto auth = authentication_.state();
    result.phase = auth.phase;
    result.status = auth.status;
    result.logs.insert(result.logs.end(), auth.logs.begin(), auth.logs.end());
    return result;
}
Result LocalClientServices::storageFailure() const {
    return {false, storageError_.empty() ? L"本地配置保存失败，请检查磁盘和目录权限。" : storageError_};
}
Result LocalClientServices::connect() {
    if (!storageError_.empty()) return storageFailure();
    if (!authentication_.available()) return {false, L"当前认证通道的原生加解密组件不可用。"};
    if (authentication_.state().phase != ConnectionPhase::offline) return {false, L"正在处理认证会话。"};
    const auto& state = model_.state_;
    const auto account = std::find_if(state.accounts.begin(), state.accounts.end(),
        [&](const auto& entry) { return entry.id == state.selectedId; });
    if (account == state.accounts.end()) return {false, L"请先选择校园网账号。"};
    auto encrypted = credentials_.value(str(account->id));
    if (encrypted.isEmpty()) return {false, L"请先保存所选账号的密码。"};
    const auto identity = deviceIdentity(QFileInfo(path_).dir().filePath("device-state.json"));
    if (!identity) return {false, L"无法读取或保存设备标识。"};
    return authentication_.start(identity->first, identity->second, str(account->username),
        [encrypted = std::move(encrypted)]() mutable { return unprotectPassword(encrypted); });
}
Result LocalClientServices::disconnect() { return authentication_.stop(); }
Result LocalClientServices::perform(ServiceAction action) { return model_.perform(action); }

Result LocalClientServices::saveAccount(const AccountInput& input) {
    if (!storageError_.empty()) return storageFailure();
    if (input.id.empty() && model_.state_.accounts.size() >= static_cast<size_t>(maxAccounts))
        return {false, L"最多保存 100 个账户。"};
    auto candidate = model_;
    const auto result = candidate.saveAccount(input);
    if (!result.ok) return result;
    const auto id = str(input.id.empty() ? candidate.state_.accounts.back().id : input.id);
    auto credentials = credentials_;
    if (!input.password.empty()) {
        const auto encrypted = protectPassword(input.password);
        if (encrypted.isEmpty() || encrypted.size() > maxCredentialBytes)
            return {false, L"Windows 无法保护该密码，请重试。"};
        credentials.insert(id, encrypted);
    } else if (!input.id.empty()) {
        const auto& accounts = model_.state_.accounts;
        const auto previous = std::find_if(accounts.begin(), accounts.end(),
            [&](const auto& account) { return account.id == input.id; });
        if (previous->username != input.username && credentials.contains(id))
            return {false, L"修改已有密码的账号时，请重新输入密码。"};
    }
    return commit(std::move(candidate), std::move(credentials))
        ? Result{true, L"账户已保存。"} : storageFailure();
}

Result LocalClientServices::selectAccount(const std::wstring& id) {
    if (!storageError_.empty()) return storageFailure();
    auto candidate = model_;
    const auto result = candidate.selectAccount(id);
    if (!result.ok || id == model_.state_.selectedId) return result;
    return commit(std::move(candidate), credentials_) ? result : storageFailure();
}

Result LocalClientServices::deleteAccount(const std::wstring& id) {
    if (!storageError_.empty()) return storageFailure();
    auto candidate = model_;
    const auto result = candidate.deleteAccount(id);
    if (!result.ok) return result;
    auto credentials = credentials_;
    credentials.remove(str(id));
    return commit(std::move(candidate), std::move(credentials))
        ? Result{true, L"账户及其已保存的密码已删除。"} : storageFailure();
}

Result LocalClientServices::saveSettings(const Settings& settings) {
    if (!storageError_.empty()) return storageFailure();
    auto candidate = model_;
    const auto result = candidate.saveSettings(settings);
    if (!result.ok) return result;
    return commit(std::move(candidate), credentials_)
        ? Result{true, L"偏好已保存。自启、自动连接和重登调度待接入。"} : storageFailure();
}
}
