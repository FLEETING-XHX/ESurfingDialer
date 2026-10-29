#pragma once
#include <cstdint>
#include <filesystem>
#include <map>
#include <string>
#include <string_view>
#include <variant>

namespace esurfing {
// Legacy state files contain flat objects of strings, integers, booleans and null.
// Reject duplicate fields, nested values, invalid UTF-8 and oversized input.
using JsonValue = std::variant<std::nullptr_t, bool, std::int64_t, std::string>;
using JsonObject = std::map<std::string, JsonValue>;
JsonObject parse_state_json(std::string_view);
std::string encode_state_json(const JsonObject&);
std::string read_state_file(const std::filesystem::path&, std::size_t limit = 32768);
void write_state_file(const std::filesystem::path&, std::string_view);
}
