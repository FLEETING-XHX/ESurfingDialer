#include "Native2082Cipher.h"

#include "esurfing/aes_cipher.hpp"
#include "esurfing/module_loader.hpp"
#include "esurfing/native_cipher.hpp"
#include "esurfing/sm4_cipher.hpp"
#include <QFile>
#include <algorithm>
#include <type_traits>
#include <variant>

static void initializeLoaderResource() {
    Q_INIT_RESOURCE(android2082_profile);
}

namespace esurfing::auth2104 {
namespace {
using Tea = native::TripleModifiedTeaCbc;
using Sm4 = native::Sm4Cbc;
using Aes = native::DoubleAes128Cbc;
using Algorithm = std::variant<Tea, Sm4, Aes>;

std::span<const std::uint8_t> bytes(QByteArrayView input) {
    return {reinterpret_cast<const std::uint8_t*>(input.data()), static_cast<size_t>(input.size())};
}

Algorithm select(const ModuleMaterial& material) {
    switch (material.cipher) {
    case ModuleCipher::triple_modified_tea_cbc:
        return Tea(material.key, material.iv);
    case ModuleCipher::sm4_cbc:
        return Sm4(material.sm4_key, material.sm4_iv);
    case ModuleCipher::double_aes128_cbc:
        return Aes(material.aes_keys, material.aes_iv);
    }
    throw Error("BOOTSTRAP_MODULE_UNSUPPORTED");
}

class Cipher2082 final : public Cipher {
public:
    explicit Cipher2082(const ModuleMaterial& material)
        : id_(QByteArray::fromStdString(material.algorithm_id)), algorithm_(select(material)) {}

    QByteArray algorithmId() const override { return id_; }

    QByteArray encrypt(QByteArrayView plaintext) override {
        if (plaintext.isEmpty() || plaintext.size() > 65536 || plaintext.contains('\0'))
            throw Error("PLAINTEXT_SIZE_INVALID");
        const auto encrypted = std::visit([&](const auto& cipher) {
            return cipher.encrypt_c_string(std::string_view(plaintext.data(), static_cast<size_t>(plaintext.size())));
        }, algorithm_);
        return QByteArrayView(reinterpret_cast<const char*>(encrypted.data()), encrypted.size()).toByteArray().toHex().toUpper();
    }

    QByteArray decrypt(QByteArrayView ciphertext) override {
        if (ciphertext.isEmpty() || ciphertext.size() > 65584 * 2 || ciphertext.size() % 2 ||
            !std::all_of(ciphertext.begin(), ciphertext.end(), [](char value) {
                return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f') ||
                    (value >= 'A' && value <= 'F');
            }))
            throw Error("CIPHERTEXT_HEX_INVALID");
        const auto input = QByteArray::fromHex(ciphertext.toByteArray());
        try {
            return std::visit([&](const auto& cipher) -> QByteArray {
                using Implementation = std::remove_cvref_t<decltype(cipher)>;
                if constexpr (std::is_same_v<Implementation, Tea>) {
                    const auto plain = cipher.decrypt_padded(bytes(input));
                    const auto end = std::find(plain.begin(), plain.end(), std::uint8_t(0));
                    if (end == plain.end() || std::distance(plain.begin(), end) > 65536 ||
                        !std::all_of(end, plain.end(), [](auto value) { return value == 0; }))
                        throw Error("CIPHERTEXT_PADDING_INVALID");
                    return QByteArray(reinterpret_cast<const char*>(plain.data()), end - plain.begin());
                } else {
                    return QByteArray::fromStdString(cipher.decrypt_c_string(bytes(input)));
                }
            }, algorithm_);
        } catch (const std::exception&) {
            throw Error("CIPHERTEXT_INVALID");
        }
    }

private:
    QByteArray id_;
    Algorithm algorithm_;
};
}

CipherFactory native2082CipherFactory() {
    static const QByteArray loader = [] {
        initializeLoaderResource();
        QFile file(QStringLiteral(":/auth2082/libdaproxy.so"));
        if (!file.open(QIODevice::ReadOnly) || file.size() != 30180) return QByteArray{};
        return file.read(30180);
    }();
    if (loader.isEmpty()) return {};
    return [](QByteArrayView module) -> std::unique_ptr<Cipher> {
        try {
            return std::make_unique<Cipher2082>(decode_module(bytes(module), bytes(loader)));
        } catch (const ModuleError&) {
            throw Error("BOOTSTRAP_MODULE_UNSUPPORTED");
        }
    };
}
}
