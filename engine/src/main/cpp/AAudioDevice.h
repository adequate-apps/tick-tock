// SPDX-License-Identifier: GPL-3.0-or-later
//
// The AAudio implementation of OutputDevice (decision 13): one stereo float
// stream, SHARED sharing mode, LOW_LATENCY performance mode, USAGE_MEDIA with
// CONTENT_TYPE_MUSIC, rate and callback size left to the device. Android
// only; the host tests use FakeDevice instead.
#pragma once

#include <aaudio/AAudio.h>

#include <cstdint>

#include "control/OutputDevice.h"
#include "render/Commands.h"

namespace ticktock {

class AAudioDevice final : public control::OutputDevice {
public:
    // `wake` runs after every data callback that left events behind and on
    // every error, so the control thread can sleep between beats.
    using WakeFn = void (*)(void* context);

    AAudioDevice(WakeFn wake, void* context) : wake_(wake), context_(context) {}
    ~AAudioDevice() override { close(); }

    control::DeviceOpen open(const control::DeviceRequest& request, control::DeviceListener& listener) override;
    bool start() override;
    void stop() override;
    void close() override;
    std::int32_t setBufferBursts(std::int32_t bursts) override;
    std::int32_t xrunCount() override;
    bool timestamp(std::int64_t& frames, std::int64_t& nanos) override;
    std::int64_t framesWritten() override;

    bool isOpen() const { return stream_ != nullptr; }

private:
    static aaudio_data_callback_result_t onData(AAudioStream* stream, void* userData, void* audioData,
                                                int32_t numFrames);
    static void onError(AAudioStream* stream, void* userData, aaudio_result_t error);

    WakeFn wake_;
    void* context_;
    AAudioStream* stream_ = nullptr;
    control::DeviceListener* listener_ = nullptr;
    std::int32_t framesPerBurst_ = 0;
};

}  // namespace ticktock
