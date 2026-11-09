#pragma once
#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace esurfing::native {
// 已识别Android32/2082模块：请求含终止NUL后PKCS7；响应终止NUL可选。
class Sm4Cbc {
    std::array<std::uint32_t,32> round_keys_{};
    std::array<std::uint8_t,16> iv_{};
public:
    Sm4Cbc(std::span<const std::uint8_t,16> key,std::span<const std::uint8_t,16> iv);
    std::vector<std::uint8_t> encrypt_c_string(std::string_view text) const;
    std::string decrypt_c_string(std::span<const std::uint8_t> ciphertext) const;
};
}
