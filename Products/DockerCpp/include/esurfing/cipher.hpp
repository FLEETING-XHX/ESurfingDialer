#pragma once
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <cstdint>

namespace esurfing {
// Candidate identification, not evidence of equivalence with the server's native module.
std::string module_candidate(std::span<const std::uint8_t> module);
class Cipher {
    struct Impl;
    std::unique_ptr<Impl> impl_;
public:
    explicit Cipher(std::string_view id);
    ~Cipher();
    Cipher(Cipher&&) noexcept;
    Cipher& operator=(Cipher&&) noexcept;
    Cipher(const Cipher&) = delete;
    Cipher& operator=(const Cipher&) = delete;
    std::string encrypt(std::string_view xml);
    std::string decrypt(std::string_view hex);
};
}
