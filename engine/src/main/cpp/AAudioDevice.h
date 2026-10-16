// SPDX-License-Identifier: GPL-3.0-or-later
//
// The AAudio implementation of OutputDevice (decision 13): one stereo float
// stream, SHARED sharing mode, LOW_LATENCY performance mode, USAGE_MEDIA with
// CONTENT_TYPE_MUSIC, rate and callback size left to the device. Android
// only; the host tests use FakeDevice instead.
#pragma once

#include <aaudio/AAudio.h>

#include <atomic>
#include <cstdint>

#include "RoutingSymbols.h"

#include "control/OutputDevice.h"
#include "render/Commands.h"

namespace ticktock {

class AAudioDevice final : public control::OutputDevice {
public:
    // `wake` runs after data, error and routing callbacks; the bridge
    // posts only when events wait, so the control thread
    // can sleep between beats.
    using WakeFn = void (*)(void* context);

    AAudioDevice(WakeFn wake, void* context, RoutingSymbols routing = RoutingSymbols::system())
        : wake_(wake), context_(context), routing_(routing) {}
    ~AAudioDevice() override { close(); }

    control::DeviceOpen open(const control::DeviceRequest& request, control::DeviceListener& listener) override;
    bool start() override;
    void stop() override;
    void close() override;
    std::int32_t setBufferBursts(std::int32_t bursts) override;
    std::int32_t xrunCount() override;
    bool timestamp(std::int64_t& frames, std::int64_t& nanos) override;
    std::int64_t framesWritten() override;
    std::int32_t routedDeviceId() override;

    bool isOpen() const { return stream_ != nullptr; }

private:
    static aaudio_data_callback_result_t onData(AAudioStream* stream, void* userData, void* audioData,
                                                int32_t numFrames);
    static void onError(AAudioStream* stream, void* userData, aaudio_result_t error);

    static void onRoutingChanged(AAudioStream* stream, void* userData, const int32_t* deviceIds, int32_t numDevices);

    WakeFn wake_;
    void* context_;
    RoutingSymbols routing_;
    // Control-thread handle remains alive until close has joined platform callbacks.
    AAudioStream* stream_ = nullptr;
    std::atomic<AAudioStream*> activeStream_{nullptr};
    control::DeviceListener* listener_ = nullptr;
    std::int32_t framesPerBurst_ = 0;
};

}  // namespace ticktock
