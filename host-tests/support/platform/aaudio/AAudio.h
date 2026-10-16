// SPDX-License-Identifier: GPL-3.0-or-later
// Minimal test declarations for the platform calls made by AAudioDevice.
#pragma once
#include <cstdint>
#include <time.h>

struct AAudioStreamStruct;
struct AAudioStreamBuilderStruct;
using AAudioStream = AAudioStreamStruct;
using AAudioStreamBuilder = AAudioStreamBuilderStruct;
using aaudio_result_t = std::int32_t;
using aaudio_data_callback_result_t = std::int32_t;
using aaudio_performance_mode_t = std::int32_t;
using aaudio_sharing_mode_t = std::int32_t;
using AAudioStream_dataCallback = aaudio_data_callback_result_t (*)(AAudioStream*, void*, void*, std::int32_t);
using AAudioStream_errorCallback = void (*)(AAudioStream*, void*, aaudio_result_t);
constexpr std::int32_t AAUDIO_OK = 0;
constexpr std::int32_t AAUDIO_ERROR_DISCONNECTED = -899;
constexpr std::int32_t AAUDIO_DIRECTION_OUTPUT = 0;
constexpr std::int32_t AAUDIO_SHARING_MODE_EXCLUSIVE = 0;
constexpr std::int32_t AAUDIO_SHARING_MODE_SHARED = 1;
constexpr std::int32_t AAUDIO_FORMAT_PCM_FLOAT = 2;
constexpr std::int32_t AAUDIO_USAGE_MEDIA = 1;
constexpr std::int32_t AAUDIO_CONTENT_TYPE_MUSIC = 2;
constexpr std::int32_t AAUDIO_CALLBACK_RESULT_CONTINUE = 0;

extern "C" {
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder**);
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder*);
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder*, AAudioStream**);
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder*, std::int32_t);
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder*, std::int32_t);
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder*, std::int32_t);
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder*, std::int32_t);
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder*, std::int32_t);
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder*, std::int32_t);
void AAudioStreamBuilder_setUsage(AAudioStreamBuilder*, std::int32_t);
void AAudioStreamBuilder_setContentType(AAudioStreamBuilder*, std::int32_t);
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder*, AAudioStream_dataCallback, void*);
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder*, AAudioStream_errorCallback, void*);
std::int32_t AAudioStream_getFramesPerBurst(AAudioStream*);
std::int32_t AAudioStream_getSampleRate(AAudioStream*);
std::int32_t AAudioStream_getDeviceId(AAudioStream*);
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream*);
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream*);
aaudio_result_t AAudioStream_requestStart(AAudioStream*);
aaudio_result_t AAudioStream_requestStop(AAudioStream*);
aaudio_result_t AAudioStream_close(AAudioStream*);
aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream*, std::int32_t);
std::int32_t AAudioStream_getXRunCount(AAudioStream*);
aaudio_result_t AAudioStream_getTimestamp(AAudioStream*, clockid_t, std::int64_t*, std::int64_t*);
std::int64_t AAudioStream_getFramesWritten(AAudioStream*);
}

// Deliberately no routing setter declaration: the approved NDK also lacks it.
