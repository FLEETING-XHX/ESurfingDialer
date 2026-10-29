#include "esurfing/core.hpp"
#include <algorithm>
#include <limits>

namespace esurfing {
const char* phase_name(Phase p) {
    switch(p) {
    case Phase::waiting_network: return "waiting_network";
    case Phase::external_online: return "external_online";
    case Phase::authorizing: return "authorizing";
    case Phase::confirming: return "confirming";
    case Phase::online: return "online";
    case Phase::backoff: return "backoff";
    case Phase::paused: return "paused";
    case Phase::stopped: return "stopped";
    }
    throw std::logic_error("INVALID_PHASE");
}
void Policy::validate() const {
    if(!max_recoveries || max_recoveries > 1000 || !deterministic_limit || !heartbeat_failure_limit
       || retry_initial.count() < 1 || retry_max < retry_initial || retry_max > Seconds(1800)
       || healthy_reset.count() < 1 || operation_timeout.count() < 1)
        throw std::invalid_argument("INVALID_POLICY");
}
Core::Core(Policy p): policy_(p) { policy_.validate(); }
bool Core::current(std::uint64_t g) const { return state_.phase != Phase::stopped && g == state_.generation; }
void Core::network(bool online) {
    state_.network_online = online;
    if(state_.phase == Phase::waiting_network || state_.phase == Phase::external_online)
        state_.phase = online ? Phase::external_online : Phase::waiting_network;
}
std::optional<std::uint64_t> Core::authorize(Mono now) {
    if(state_.phase == Phase::paused || state_.phase == Phase::stopped
       || state_.phase == Phase::authorizing || state_.phase == Phase::confirming) return {};
    if(state_.phase == Phase::backoff && now < next_retry_) return {};
    // Only a caller holding an observed portal candidate can request authorization.
    ++state_.generation;
    state_.phase = Phase::authorizing;
    state_.authenticated = false;
    state_.heartbeat_failures = 0;
    heartbeat_due_.reset();
    progress_ = now;
    return state_.generation;
}
bool Core::login_accepted(std::uint64_t g, Mono now) {
    if(!current(g) || state_.phase != Phase::authorizing) return false;
    state_.phase = Phase::confirming;
    progress_ = now;
    return true;
}
void Core::progress(std::uint64_t g, Mono now) {
    if(current(g)&&(state_.phase==Phase::authorizing||state_.phase==Phase::confirming))progress_=now;
}
bool Core::keep_confirmed(std::uint64_t g, Seconds interval, Mono now) {
    if(!current(g) || (state_.phase != Phase::confirming && state_.phase != Phase::online)) return false;
    if(interval.count() <= 0) { failed(g, "HEARTBEAT_INTERVAL_INVALID", now, true); return false; }
    if(state_.phase != Phase::online) healthy_since_ = now;
    heartbeat_interval_ = std::clamp(interval, Seconds(5), Seconds(600));
    heartbeat_due_ = now + heartbeat_interval_;
    state_.phase = Phase::online;
    state_.authenticated = true;
    state_.heartbeat_failures = 0;
    state_.error.clear();
    deterministic_failures_ = 0;
    last_deterministic_.clear();
    progress_ = now;
    // A momentary good snapshot does not reset the recovery budget.
    tick(now);
    return true;
}
void Core::recover(std::string code, Mono now, bool deterministic) {
    state_.authenticated = false;
    heartbeat_due_.reset();
    state_.error = std::move(code);
    if(deterministic) {
        if(state_.error != last_deterministic_) deterministic_failures_ = 0;
        last_deterministic_ = state_.error;
        ++deterministic_failures_;
    } else { deterministic_failures_ = 0; last_deterministic_.clear(); }
    // Invalidate every outstanding result, including keep and delayed transport callbacks.
    ++state_.generation;
    if(++state_.recovery_attempts >= policy_.max_recoveries
       || deterministic_failures_ >= policy_.deterministic_limit) {
        state_.phase = Phase::paused;
        return;
    }
    unsigned shifts = std::min(state_.recovery_attempts - 1, 10u);
    auto delay = Seconds(std::min(policy_.retry_max.count(), policy_.retry_initial.count() * (Seconds::rep{1} << shifts)));
    next_retry_ = now + delay;
    state_.phase = Phase::backoff;
}
void Core::failed(std::uint64_t g, std::string code, Mono now, bool deterministic) {
    if(!current(g) || state_.phase == Phase::paused) return;
    // Accept machine codes only; never carry exception text or response bodies into health/logs.
    if(code.empty() || code.size() > 80 || code.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789") != std::string::npos)
        code = "INTERNAL_FAILURE";
    recover(std::move(code), now, deterministic);
}
bool Core::heartbeat_due(Mono now) const {
    return state_.authenticated && heartbeat_due_ && now >= *heartbeat_due_;
}
void Core::heartbeat_failed(std::uint64_t g, Mono now, bool protocol_rejected) {
    if(!current(g) || state_.phase != Phase::online) return;
    state_.error = protocol_rejected?"HEARTBEAT_PROTOCOL_INVALID":"HEARTBEAT_TRANSPORT_ERROR";
    healthy_since_ = now;
    ++state_.heartbeat_failures;
    if(state_.heartbeat_failures >= policy_.heartbeat_failure_limit) recover(state_.error, now, false);
    else heartbeat_due_ = now + Seconds(std::min(state_.heartbeat_failures * 5u, 30u));
}
void Core::tick(Mono now) {
    if((state_.phase == Phase::authorizing || state_.phase == Phase::confirming)
       && now - progress_ >= policy_.operation_timeout) recover("AUTH_OPERATION_TIMEOUT", now, false);
    if(state_.phase == Phase::online && heartbeat_due_ && now - *heartbeat_due_ >= policy_.operation_timeout) {
        recover("HEARTBEAT_PROGRESS_TIMEOUT", now, false);
        return;
    }
    if(state_.phase == Phase::online && state_.heartbeat_failures == 0 && heartbeat_due_ && now < *heartbeat_due_
       && now - healthy_since_ >= policy_.healthy_reset)
        state_.recovery_attempts = 0;
}
void Core::stop() {
    ++state_.generation;
    state_.authenticated = false;
    state_.phase = Phase::stopped;
    heartbeat_due_.reset();
}
void Core::resume() {
    if(state_.phase != Phase::stopped && state_.phase != Phase::paused) return;
    ++state_.generation;
    state_.phase = state_.network_online ? Phase::external_online : Phase::waiting_network;
    state_.recovery_attempts = state_.heartbeat_failures = deterministic_failures_ = 0;
    state_.error.clear();
    last_deterministic_.clear();
}
std::string json_quote(const std::string& value) {
    std::string result = "\"";
    const char* hex = "0123456789abcdef";
    for(unsigned char c : value) {
        if(c == '"' || c == '\\') { result += '\\'; result += static_cast<char>(c); }
        else if(c < 32) { result += "\\u00"; result += hex[c >> 4]; result += hex[c & 15]; }
        else result += static_cast<char>(c);
    }
    return result + '"';
}
}
