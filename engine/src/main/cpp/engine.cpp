// SPDX-License-Identifier: GPL-3.0-or-later
//
// The ABI version the Kotlin side checks at load. Bumped whenever the JNI
// shape in JniBridge.cpp changes; EngineLibrary.ABI_VERSION must match.
#include <cstdint>

namespace ticktock {

constexpr std::int32_t kEngineAbiVersion = 4;

}  // namespace ticktock

extern "C" std::int32_t ticktock_engine_abi_version() { return ticktock::kEngineAbiVersion; }
