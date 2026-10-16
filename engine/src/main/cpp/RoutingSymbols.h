// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <aaudio/AAudio.h>
#include <cstdint>

namespace ticktock {

// API 37's public ABI, resolved at runtime so the approved older NDK and
// devices without the symbol retain the disconnect-based route fallback.
struct RoutingSymbols {
    using Callback = void (*)(AAudioStream*, void*, const std::int32_t*, std::int32_t);
    using Setter = void (*)(AAudioStreamBuilder*, Callback, void*);
    using Lookup = void* (*)(const char*);
    Setter setCallback = nullptr;

    static RoutingSymbols resolve(int apiLevel, Lookup lookup) {
        if (apiLevel < 37) return {};
        return {reinterpret_cast<Setter>(lookup("AAudioStreamBuilder_setRoutingChangedCallback"))};
    }
    // Called only while constructing the device, never on a callback thread.
    static RoutingSymbols system();
};

}  // namespace ticktock
