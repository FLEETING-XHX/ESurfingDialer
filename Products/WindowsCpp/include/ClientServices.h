#pragma once
#include <optional>
#include <string>
#include <vector>

namespace esurfing {
enum class ConnectionPhase { offline, connecting, online, recovering, stopping, unavailable };
enum class ServiceAction { exportLogs, changePassword, recharge, projectHome };
struct Result { bool ok; std::wstring message; };
// Account summaries never contain secrets. A future credential adapter owns DPAPI storage.
struct Account { std::wstring id, label, username; bool operator==(const Account&) const=default; };
struct AccountInput { std::wstring id, label, username, password; };
struct Settings {
    bool autoStart=false, startMinimized=false, autoConnect=false, backgroundNotice=true;
    bool dailyReauth=false, enhancedConnection=true;
    int startHour=4, endHour=6;
    bool operator==(const Settings&) const=default;
};
struct Traffic { bool available=false; double downloadKB=0, uploadKB=0; std::vector<double> download, upload; bool operator==(const Traffic&) const=default; };
struct Snapshot {
    ConnectionPhase phase=ConnectionPhase::unavailable;
    std::wstring status=L"认证服务尚未接入";
    std::vector<Account> accounts;
    std::wstring selectedId;
    Settings settings;
    Traffic traffic;
    std::vector<std::wstring> logs;
    bool operator==(const Snapshot&) const=default;
};
// Authentication and export adapters enqueue work and publish immutable snapshots.
// Never run HTTP or blocking shutdown on the UI thread.
// snapshot() returns a coherent copy and must be thread-safe if a worker updates state.
// Commands report acceptance only; online is published ONLY after confirmed authentication.
class IClientServices {
public:
    virtual ~IClientServices()=default;
    virtual Snapshot snapshot() const=0;
    virtual Result connect()=0;
    virtual Result disconnect()=0;
    virtual Result saveAccount(const AccountInput&)=0;
    virtual Result selectAccount(const std::wstring&)=0;
    virtual Result deleteAccount(const std::wstring&)=0;
    virtual Result saveSettings(const Settings&)=0;
    virtual Result perform(ServiceAction)=0;
};
// UI migration adapter: memory-only account metadata/settings, no network, credential storage,
// registry, shell launch or fabricated successful authentication.
class PreviewServices final : public IClientServices {
public:
    PreviewServices();
    Snapshot snapshot() const override;
    Result connect() override;
    Result disconnect() override;
    Result saveAccount(const AccountInput&) override;
    Result selectAccount(const std::wstring&) override;
    Result deleteAccount(const std::wstring&) override;
    Result saveSettings(const Settings&) override;
    Result perform(ServiceAction) override;
private:
    friend class LocalClientServices;
    Snapshot state_;
    unsigned nextId_=1;
};
}
