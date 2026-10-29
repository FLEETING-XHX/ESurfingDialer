#pragma once
#include <filesystem>
#include <functional>
#include <string>

namespace esurfing {
struct DeviceIdentity { std::string client_id, mac; };
// Caller must hold the StateStore directory lock throughout load/write/use.
DeviceIdentity load_identity(const std::filesystem::path&, const std::function<std::string(const char*)>& env);
}
