#pragma once
#include <array>
#include <cstdint>
#include <span>
#include <string_view>
#include <vector>

// Android32/2082已验证原语；参数由模块提取，不按Algo-ID选择，不含样本密钥。
namespace esurfing::native {
class TripleModifiedTeaCbc {
    std::array<std::array<std::uint32_t,4>,3> keys_{};
    std::array<std::uint32_t,2> iv_{};
public:
    TripleModifiedTeaCbc(std::span<const std::uint8_t,48> key, std::span<const std::uint8_t,8> iv);
    std::vector<std::uint8_t> encrypt_c_string(std::string_view text) const;
    std::vector<std::uint8_t> decrypt_padded(std::span<const std::uint8_t> ciphertext) const;
};
}
