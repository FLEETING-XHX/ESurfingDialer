#pragma once
// 旧诊断接口兼容转发；生产原语是唯一实现来源。
#include "../../include/esurfing/native_cipher.hpp"
namespace recovered {
using TripleModifiedTeaCbc = esurfing::native::TripleModifiedTeaCbc;
}
