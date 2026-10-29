#pragma once
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <filesystem>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

namespace esurfing {
void log_emergency(const std::string& code) noexcept;
struct HeartbeatNotice { bool recovered=false; std::uint64_t successes=0; unsigned failures=0; long long retry_seconds=0; };
class HeartbeatLog {
    std::optional<std::chrono::steady_clock::time_point> last_;
    std::uint64_t successes_=0;
public:
    void reset() { last_.reset();successes_=0; }
    std::optional<HeartbeatNotice> success(unsigned failures, long long retry_seconds, std::chrono::steady_clock::time_point now);
};
struct LogConfig {
    std::filesystem::path directory;
    std::uintmax_t file_bytes=10485760, total_bytes=52428800;
    unsigned rotated=2, retention_days=3;
    static LogConfig read(const std::filesystem::path& state, const std::function<std::string(const char*)>& env);
};
// One owner per log directory. Only exact legacy dialer date/rotation names are managed.
class LogFiles {
    LogConfig config_;
    std::intptr_t lock_=-1;
    std::string day_;
    void check(const std::filesystem::path&) const;
    void rotate(const std::filesystem::path&);
public:
    explicit LogFiles(LogConfig);
    ~LogFiles();
    LogFiles(const LogFiles&)=delete;
    void maintain(std::chrono::system_clock::time_point, const std::string& day);
    void append(std::chrono::system_clock::time_point, const std::string& line);
};
enum class LogEvent { state, login_confirmed, heartbeat_failed, auth_failed, scheduled_reauth,
    term_sent, term_failed, monitor_failed, stopped };
// Producers never perform I/O. Overflow or a failed sink is fatal, not silent log loss.
class EventLog {
    struct Record { std::chrono::system_clock::time_point time; std::string text; };
    LogFiles files_;
    std::function<void(const std::string&)> output_;
    std::mutex mutex_;
    std::condition_variable ready_;
    std::deque<Record> queue_;
    bool closing_=false;
    std::atomic_bool failed_=false;
    std::jthread worker_;
    void run() noexcept;
    void enqueue(std::string) noexcept;
public:
    explicit EventLog(LogConfig, std::function<void(const std::string&)> output={});
    ~EventLog();
    void write(LogEvent, const std::string& code={}) noexcept;
    void heartbeat(const HeartbeatNotice&) noexcept;
    bool failed() const { return failed_.load(); }
    bool finish() noexcept;
};
}
