#pragma once
#include <array>
#include <cstdint>
#include <filesystem>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

namespace esurfing {
class ModuleError final : public std::runtime_error {
public:
    explicit ModuleError(const char* code) : std::runtime_error(code) {}
    const char* code() const noexcept { return what(); }
};

enum class ModuleCipher { triple_modified_tea_cbc, sm4_cbc };
struct ModuleMaterial {
    ModuleCipher cipher=ModuleCipher::triple_modified_tea_cbc;
    std::string algorithm_id;
    std::array<std::uint8_t, 48> key{};
    std::array<std::uint8_t, 8> iv{};
    std::array<std::uint8_t, 16> sm4_key{},sm4_iv{};
};
struct ModuleSummary {
    std::size_t bytes=0;
    std::string sha256;
    int encoding=-1;
};
ModuleSummary summarize_module(std::span<const std::uint8_t> module);
// 显式取证开关开启时使用；最多3个槽，仅保存bootstrap响应，不保存认证XML。
void capture_bootstrap(const std::filesystem::path& directory, unsigned slot,
                       std::span<const std::uint8_t> module, const ModuleSummary& summary);

// 只解码受控001/type0或type3容器，不执行ARM；内层TEA/SM4按完整实现指纹识别。
ModuleMaterial decode_module(std::span<const std::uint8_t> module,
                             std::span<const std::uint8_t> native_library);
// 仅读取调用者明确提供的本地普通文件；验证完整摘要后返回，不搜索/下载原生库。
std::vector<std::uint8_t> read_known_native_library(const std::filesystem::path& path);
}
