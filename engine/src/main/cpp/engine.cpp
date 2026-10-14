// SPDX-License-Identifier: GPL-3.0-or-later
//
// Placeholder translation unit so the native target links. The mixer, voices,
// click generator and beat generator arrive in M2 behind the frozen
// OutputCommand/OutputEvent contract.
#include <cstdint>

namespace ticktock {

// Bumped whenever the JNI shape changes; the Kotlin side checks it at load.
constexpr std::int32_t kEngineAbiVersion = 1;

extern "C" std::int32_t ticktock_engine_abi_version() { return kEngineAbiVersion; }

}  // namespace ticktock
