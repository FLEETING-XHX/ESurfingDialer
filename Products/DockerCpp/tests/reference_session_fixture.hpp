#pragma once
#include "esurfing/session.hpp"

// Only test sources and the separately compiled runtime fixture include this.
// A real 001 container always uses production decoding, even in the fixture.
inline esurfing::Session::CipherFactory reference_session_cipher_factory() {
    return [](std::span<const std::uint8_t> bytes)->std::unique_ptr<esurfing::Cipher>{
        if(bytes.size()>=3&&bytes[0]==0&&bytes[1]==0&&bytes[2]==0)
            return std::make_unique<esurfing::Cipher>(esurfing::module_candidate(bytes));
        return esurfing::load_session_cipher(bytes);
    };
}
