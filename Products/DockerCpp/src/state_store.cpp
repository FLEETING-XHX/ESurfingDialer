#include "esurfing/state_store.hpp"
#include <fstream>
#include <random>
#include <sstream>
#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#else
#include <sys/file.h>
#include <fcntl.h>
#include <unistd.h>
#endif

namespace esurfing {
StateStore::StateStore(std::filesystem::path directory): directory_(std::move(directory)) {
    std::filesystem::create_directories(directory_);
    auto lock = directory_ / "native-owner.lock";
#ifdef _WIN32
    auto h = CreateFileW(lock.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if(h == INVALID_HANDLE_VALUE) throw std::runtime_error("STATE_DIRECTORY_OWNED");
    handle_ = reinterpret_cast<std::intptr_t>(h);
#else
    int fd = open(lock.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    if(fd < 0) throw std::runtime_error("STATE_LOCK_OPEN_FAILED");
    if(flock(fd, LOCK_EX | LOCK_NB)) { close(fd); throw std::runtime_error("STATE_DIRECTORY_OWNED"); }
    handle_ = fd;
#endif
    try {
        std::random_device random;
        std::ostringstream id;
        id << std::hex << random() << '-' << random() << '-' << random() << '-' << random();
        instance_ = id.str();
    } catch(...) {
#ifdef _WIN32
        CloseHandle(reinterpret_cast<HANDLE>(handle_));
#else
        close(static_cast<int>(handle_));
#endif
        handle_ = -1;
        throw;
    }
}
StateStore::~StateStore() {
    if(handle_ == -1) return;
#ifdef _WIN32
    CloseHandle(reinterpret_cast<HANDLE>(handle_));
#else
    close(static_cast<int>(handle_));
#endif
}
void StateStore::write(const Snapshot& s, bool alive, std::int64_t epoch,long long report_interval,const PlanSnapshot* plan) {
    if(epoch <= 0||report_interval<1||report_interval>300) throw std::invalid_argument("INVALID_HEALTH_POLICY");
    auto temporary = directory_ / ("health.json." + instance_ + ".tmp");
    auto target = directory_ / "health.json";
    try {
        std::ofstream file(temporary, std::ios::binary | std::ios::trunc);
        file.exceptions(std::ios::badbit | std::ios::failbit);
        // Schema 3 deliberately separates external reachability and owned authentication.
        // Existing schema-2 scripts must be adapted before deployment (DCPP-4).
        const auto pid=
#ifdef _WIN32
            GetCurrentProcessId();
#else
            getpid();
#endif
        file << "{\n  \"schemaVersion\": 3,\n  \"implementation\": \"cpp\",\n  \"processId\": " << pid
             << ",\n  \"instanceId\": " << json_quote(instance_)
             << ",\n  \"processAlive\": " << (alive ? "true" : "false")
             << ",\n  \"networkOnline\": " << (s.network_online ? "true" : "false")
             << ",\n  \"authenticated\": " << (s.authenticated ? "true" : "false")
             << ",\n  \"phase\": " << json_quote(phase_name(s.phase))
             << ",\n  \"generation\": " << s.generation
             << ",\n  \"recoveryAttempts\": " << s.recovery_attempts
             << ",\n  \"lastUpdatedAt\": " << epoch
             << ",\n  \"healthWriteIntervalSeconds\": " << report_interval
             << ",\n  \"lastError\": " << (s.error.empty() ? "null" : json_quote(s.error));
        if(plan)file << ",\n  \"autoReauthEnabled\": " << (plan->enabled?"true":"false")
             << ",\n  \"autoReauthEstimatedLifetimeSeconds\": " << plan->estimate
             << ",\n  \"autoReauthLastAuthSuccessAt\": " << plan->last_auth
             << ",\n  \"autoReauthNextPlannedAt\": " << plan->next
             << ",\n  \"autoReauthLastObservedLifetimeSeconds\": " << plan->observed
             << ",\n  \"autoReauthSuccessfulAuthSamples\": " << plan->samples
             << ",\n  \"autoReauthSafeWindowStartHour\": " << plan->start_hour
             << ",\n  \"autoReauthSafeWindowEndHour\": " << plan->end_hour
             << ",\n  \"autoReauthPersistenceOk\": " << (plan->persistence_ok?"true":"false");
        file << "\n}\n";
        file.close();
#ifdef _WIN32
        if(!MoveFileExW(temporary.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
            throw std::runtime_error("HEALTH_REPLACE_FAILED");
#else
        std::filesystem::rename(temporary, target);
#endif
    } catch(...) { std::error_code ec; std::filesystem::remove(temporary, ec); throw; }
}
}
