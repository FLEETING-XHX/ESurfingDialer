#pragma once
#include <array>
#include <span>
#include <string>
#include <string_view>
#include <vector>
#include <cstdint>

namespace esurfing::native {
class DoubleAes128Cbc {
    std::array<std::uint8_t,176> first_{}, second_{};
    std::array<std::uint8_t,16> iv_{};
public:
    DoubleAes128Cbc(const std::array<std::uint8_t,32>& keys, const std::array<std::uint8_t,16>& iv);
    std::vector<std::uint8_t> encrypt_c_string(std::string_view text) const;
    std::string decrypt_c_string(std::span<const std::uint8_t> bytes) const;
};
}
