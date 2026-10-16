// SPDX-License-Identifier: GPL-3.0-or-later
//
// The one seam between the controller and the platform. AAudioDevice is the
// device on Android; the host tests drive the controller with a fake that
// can refuse rates, count xruns, report timestamps and disconnect.
#pragma once

#include <cstdint>

namespace ticktock::control {

// Mirrors the AAudio constants the stream asks for (decision 13); listed so
// a test can assert what was requested.
enum class SharingMode : std::int32_t { Exclusive = 0, Shared = 1 };
enum class PerformanceMode : std::int32_t { None = 10, PowerSaving = 11, LowLatency = 12 };

struct DeviceRequest {
    // 0 leaves the rate to the device, which is what gets a low-latency path.
    std::int32_t sampleRate = 0;
    SharingMode sharing = SharingMode::Shared;
    PerformanceMode performance = PerformanceMode::LowLatency;
};

struct DeviceOpen {
    bool ok = false;
    std::int32_t errorCode = 0;
    std::int32_t sampleRate = 0;
    std::int32_t framesPerBurst = 0;
    std::int32_t deviceId = 0;
    SharingMode sharing = SharingMode::Shared;
    PerformanceMode performance = PerformanceMode::LowLatency;
};

// Error codes the controller distinguishes; any other code is "failed".
constexpr std::int32_t kErrorDisconnected = -899;  // AAUDIO_ERROR_DISCONNECTED

class DeviceListener {
public:
    virtual ~DeviceListener() = default;
    // Audio thread: fill `frames` interleaved stereo float frames.
    virtual void onRender(float* interleaved, std::int32_t frames) = 0;
    // Audio-side error thread: the stream is dead; the controller closes it
    // from its own thread later, never from here (the AAudio guide's rule).
    virtual void onError(std::int32_t errorCode) = 0;
    // Framework routing thread: signal only; platform reads belong to the control thread.
    virtual void onRoutingChanged() = 0;
};

class OutputDevice {
public:
    virtual ~OutputDevice() = default;
    virtual DeviceOpen open(const DeviceRequest& request, DeviceListener& listener) = 0;
    virtual bool start() = 0;
    virtual void stop() = 0;
    virtual void close() = 0;
    // Buffer size as a multiple of the burst; returns the size the device set.
    virtual std::int32_t setBufferBursts(std::int32_t bursts) = 0;
    virtual std::int32_t xrunCount() = 0;
    // Presentation timestamp: frame `frames` left the device at `nanos`
    // (CLOCK_MONOTONIC). False until the stream has run a few bursts.
    virtual bool timestamp(std::int64_t& frames, std::int64_t& nanos) = 0;
    virtual std::int64_t framesWritten() = 0;
    virtual std::int32_t routedDeviceId() = 0;
};

}  // namespace ticktock::control
