#pragma once
#include <cstdint>
#include <string_view>

namespace esurfing {
enum class Operation { unknown, probe, discovery, bootstrap, ticket, login, keep, term };
struct RequestDiagnostic {
    Operation operation=Operation::unknown;
    std::uint64_t attempt=0;
    std::int64_t elapsed_ms=0;
    int transport_code=0;
    long http_status=0;
};
const char* operation_name(Operation) noexcept;
const char* diagnostic_code(std::string_view) noexcept;
const char* diagnostic_reason(std::string_view) noexcept;
const char* diagnostic_domain(std::string_view) noexcept;
std::uint64_t next_request_attempt() noexcept;
}
