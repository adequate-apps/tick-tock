// SPDX-License-Identifier: GPL-3.0-or-later
//
// A scriptable OutputDevice: refuses rates on request, counts xruns the test
// bumps, hands out timestamps, and can disconnect. render() pulls audio the
// way the AAudio callback would.
#pragma once

#include <cstdint>
#include <vector>

#include "control/OutputDevice.h"

namespace ticktock::test {

class FakeDevice final : public control::OutputDevice {
public:
    // What the next open() answers.
    std::int32_t nativeRate = 48000;
    std::int32_t framesPerBurst = 192;
    std::int32_t deviceId = 7;
    bool refuseAll = false;
    bool refuseUnspecified = false;
    // The most bursts the device will size its buffer to; a request beyond it is clamped, as AAudio clamps to the capacity.
    std::int32_t maxBursts = 64;
    std::int32_t minimumBursts = 0;
    // When set, a buffer request errors, which AAudioDevice reports as 0.
    bool refuseBufferChanges = false;

    // Observations.
    std::vector<control::DeviceRequest> requests;
    // Recorded into a fixed array: setBufferBursts runs on the render thread
    // in the alloc variant, where the fake must not reach the heap either.
    std::int32_t burstsRecorded[64] = {};
    int burstsCount = 0;
    int opens = 0, starts = 0, stops = 0, closes = 0;
    bool open_ = false, started_ = false;
    std::int32_t xruns = 0;
    bool timestampValid = false;
    std::int64_t tsFrames = 0, tsNanos = 0;
    std::int64_t written = 0;

    control::DeviceOpen open(const control::DeviceRequest& request, control::DeviceListener& listener) override {
        requests.push_back(request);
        ++opens;
        control::DeviceOpen result;
        if (refuseAll || (refuseUnspecified && request.sampleRate == 0)) {
            result.ok = false;
            result.errorCode = -1;
            result.deviceId = deviceId;
            return result;
        }
        listener_ = &listener;
        open_ = true;
        written = 0;
        result.ok = true;
        result.sampleRate = request.sampleRate == 0 ? nativeRate : request.sampleRate;
        result.framesPerBurst = framesPerBurst;
        result.deviceId = deviceId;
        result.sharing = request.sharing;
        result.performance = request.performance;
        return result;
    }
    bool start() override { ++starts; started_ = open_; return open_; }
    void stop() override { ++stops; started_ = false; }
    void close() override { ++closes; open_ = false; listener_ = nullptr; }
    std::int32_t setBufferBursts(std::int32_t bursts) override {
        if (burstsCount < 64) burstsRecorded[burstsCount++] = bursts;
        if (refuseBufferChanges) return 0;
        const std::int32_t granted = bursts < maxBursts ? bursts : maxBursts;
        return (granted > minimumBursts ? granted : minimumBursts) * framesPerBurst;
    }
    std::vector<std::int32_t> bufferBurstsSet() const {
        return std::vector<std::int32_t>(burstsRecorded, burstsRecorded + burstsCount);
    }
    std::int32_t xrunCount() override { return xruns; }
    bool timestamp(std::int64_t& frames, std::int64_t& nanos) override {
        frames = tsFrames;
        nanos = tsNanos;
        return timestampValid;
    }
    std::int64_t framesWritten() override { return written; }

    // The audio thread's side.
    void render(float* interleaved, std::int32_t frames) {
        if (listener_ == nullptr || !started_) return;
        listener_->onRender(interleaved, frames);
        written += frames;
    }
    void disconnect(std::int32_t code = control::kErrorDisconnected) {
        if (listener_ != nullptr) listener_->onError(code);
    }
    bool running() const { return open_ && started_; }

private:
    control::DeviceListener* listener_ = nullptr;
};

}  // namespace ticktock::test
