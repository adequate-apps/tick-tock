// SPDX-License-Identifier: GPL-3.0-or-later
#include "AAudioDevice.h"

#include <time.h>

namespace ticktock {

static_assert(AAUDIO_ERROR_DISCONNECTED == control::kErrorDisconnected,
              "the controller's disconnect code must match AAudio's");

control::DeviceOpen AAudioDevice::open(const control::DeviceRequest& request, control::DeviceListener& listener) {
    control::DeviceOpen result;
    if (stream_ != nullptr) close();

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t rc = AAudio_createStreamBuilder(&builder);
    if (rc != AAUDIO_OK || builder == nullptr) {
        result.errorCode = rc;
        return result;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, request.sharing == control::SharingMode::Exclusive
                                                    ? AAUDIO_SHARING_MODE_EXCLUSIVE
                                                    : AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, static_cast<aaudio_performance_mode_t>(request.performance));
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, render::kChannels);
    if (request.sampleRate > 0) AAudioStreamBuilder_setSampleRate(builder, request.sampleRate);
    // Media attributes so the stream mixes with a music player instead of
    // ducking it (decision 4); no audio focus is involved anywhere.
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(builder, &AAudioDevice::onData, this);
    AAudioStreamBuilder_setErrorCallback(builder, &AAudioDevice::onError, this);

    listener_ = &listener;
    rc = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    if (rc != AAUDIO_OK || stream_ == nullptr) {
        stream_ = nullptr;
        listener_ = nullptr;
        result.errorCode = rc;
        return result;
    }

    framesPerBurst_ = AAudioStream_getFramesPerBurst(stream_);
    result.ok = true;
    result.sampleRate = AAudioStream_getSampleRate(stream_);
    result.framesPerBurst = framesPerBurst_;
    result.deviceId = AAudioStream_getDeviceId(stream_);
    result.sharing = AAudioStream_getSharingMode(stream_) == AAUDIO_SHARING_MODE_EXCLUSIVE
                         ? control::SharingMode::Exclusive
                         : control::SharingMode::Shared;
    result.performance = static_cast<control::PerformanceMode>(AAudioStream_getPerformanceMode(stream_));
    return result;
}

bool AAudioDevice::start() {
    return stream_ != nullptr && AAudioStream_requestStart(stream_) == AAUDIO_OK;
}

void AAudioDevice::stop() {
    if (stream_ != nullptr) AAudioStream_requestStop(stream_);
}

void AAudioDevice::close() {
    if (stream_ == nullptr) return;
    // Always from the control thread, never from the error callback.
    AAudioStream_close(stream_);
    stream_ = nullptr;
    listener_ = nullptr;
}

std::int32_t AAudioDevice::setBufferBursts(std::int32_t bursts) {
    if (stream_ == nullptr || framesPerBurst_ <= 0) return 0;
    const aaudio_result_t rc = AAudioStream_setBufferSizeInFrames(stream_, bursts * framesPerBurst_);
    return rc < 0 ? 0 : rc;
}

std::int32_t AAudioDevice::xrunCount() {
    if (stream_ == nullptr) return 0;
    const std::int32_t n = AAudioStream_getXRunCount(stream_);
    return n < 0 ? 0 : n;
}

bool AAudioDevice::timestamp(std::int64_t& frames, std::int64_t& nanos) {
    if (stream_ == nullptr) return false;
    int64_t position = 0;
    int64_t time = 0;
    if (AAudioStream_getTimestamp(stream_, CLOCK_MONOTONIC, &position, &time) != AAUDIO_OK) return false;
    frames = position;
    nanos = time;
    return true;
}

std::int64_t AAudioDevice::framesWritten() {
    return stream_ == nullptr ? 0 : AAudioStream_getFramesWritten(stream_);
}

aaudio_data_callback_result_t AAudioDevice::onData(AAudioStream*, void* userData, void* audioData, int32_t numFrames) {
    auto* self = static_cast<AAudioDevice*>(userData);
    if (self->listener_ != nullptr) self->listener_->onRender(static_cast<float*>(audioData), numFrames);
    if (self->wake_ != nullptr) self->wake_(self->context_);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioDevice::onError(AAudioStream*, void* userData, aaudio_result_t error) {
    auto* self = static_cast<AAudioDevice*>(userData);
    if (self->listener_ != nullptr) self->listener_->onError(error);
    if (self->wake_ != nullptr) self->wake_(self->context_);
}

}  // namespace ticktock
