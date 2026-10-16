// SPDX-License-Identifier: GPL-3.0-or-later
// The actual platform device and bridge run against scriptable platform entry points.
#include "AAudioDevice.h"
#include "RoutingSymbols.h"
#include "support/AllocCounter.h"
#include <gtest/gtest.h>
#include <algorithm>
#include <cstring>
#include <memory>
#include <vector>

// Including this translation unit lets the lifetime tests exercise its actual private Bridge.
#include "../engine/src/main/cpp/JniBridge.cpp"

using RoutingCallback = void (*)(AAudioStream*, void*, const std::int32_t*, std::int32_t);
struct AAudioStreamBuilderStruct {
    int rate = 48000;
    AAudioStream_dataCallback data = nullptr;
    AAudioStream_errorCallback error = nullptr;
    RoutingCallback routing = nullptr;
    void* dataUser = nullptr;
    void* errorUser = nullptr;
    void* routeUser = nullptr;
};
struct AAudioStreamStruct : AAudioStreamBuilderStruct {
    int device = 7;
    bool closed = false;
};

namespace {
struct Platform {
    std::vector<std::unique_ptr<AAudioStream>> streams;
    std::vector<int> lifetime;
    int api = 36;
    int lookups = 0;
    int registrations = 0;
    int deviceReads = 0;
    int wakes = 0;
    bool symbol = true;
    bool callbackDuringClose = false;
    bool watchLifetime = false;
} platform;

void setRouting(AAudioStreamBuilder* builder, RoutingCallback callback, void* data) {
    ++platform.registrations;
    builder->routing = callback;
    builder->routeUser = data;
}
void* lookup(const char* name) {
    ++platform.lookups;
    EXPECT_STREQ("AAudioStreamBuilder_setRoutingChangedCallback", name);
    return platform.symbol ? reinterpret_cast<void*>(&setRouting) : nullptr;
}
void wake(void*) { ++platform.wakes; }
void route(AAudioStream* stream) {
    const std::int32_t ids[] = {42, 43};
    if (stream->routing) stream->routing(stream, stream->routeUser, ids, 2);
}
struct Listener : ticktock::control::DeviceListener {
    int routes = 0, errors = 0, renders = 0;
    void onRender(float*, std::int32_t) override { ++renders; }
    void onError(std::int32_t) override { ++errors; }
    void onRoutingChanged() override { ++routes; }
};
class AAudioRouting : public ::testing::Test {
    void SetUp() override { platform = Platform{}; }
};
}  // namespace

extern "C" {
int android_get_device_api_level() { return platform.api; }
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder** out) { *out = new AAudioStreamBuilder; return 0; }
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder* b) { delete b; return 0; }
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder* b, AAudioStream** out) {
    auto stream = std::make_unique<AAudioStream>();
    static_cast<AAudioStreamBuilderStruct&>(*stream) = *b;
    *out = stream.get();
    platform.streams.push_back(std::move(stream));
    return 0;
}
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder*, std::int32_t) {}
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder*, std::int32_t) {}
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder*, std::int32_t) {}
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder*, std::int32_t) {}
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder*, std::int32_t) {}
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder* b, std::int32_t value) { b->rate = value; }
void AAudioStreamBuilder_setUsage(AAudioStreamBuilder*, std::int32_t) {}
void AAudioStreamBuilder_setContentType(AAudioStreamBuilder*, std::int32_t) {}
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder* b, AAudioStream_dataCallback cb, void* data) { b->data = cb; b->dataUser = data; }
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder* b, AAudioStream_errorCallback cb, void* data) { b->error = cb; b->errorUser = data; }
std::int32_t AAudioStream_getFramesPerBurst(AAudioStream*) { return 192; }
std::int32_t AAudioStream_getSampleRate(AAudioStream* s) { return s->rate; }
std::int32_t AAudioStream_getDeviceId(AAudioStream* s) { ++platform.deviceReads; return s->device; }
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream*) { return AAUDIO_SHARING_MODE_SHARED; }
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream*) { return 12; }
aaudio_result_t AAudioStream_requestStart(AAudioStream*) { return 0; }
aaudio_result_t AAudioStream_requestStop(AAudioStream*) { return 0; }
aaudio_result_t AAudioStream_close(AAudioStream* s) {
    if (platform.watchLifetime) platform.lifetime.push_back(1);
    if (platform.callbackDuringClose) route(s);
    s->closed = true;
    if (platform.watchLifetime) platform.lifetime.push_back(2);
    return 0;
}
aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream*, std::int32_t frames) { return frames; }
std::int32_t AAudioStream_getXRunCount(AAudioStream*) { return 0; }
aaudio_result_t AAudioStream_getTimestamp(AAudioStream*, clockid_t, std::int64_t*, std::int64_t*) { return -1; }
std::int64_t AAudioStream_getFramesWritten(AAudioStream*) { return 0; }

int __real_sem_destroy(sem_t*);
int __wrap_sem_destroy(sem_t* semaphore) {
    if (platform.watchLifetime) platform.lifetime.push_back(3);
    return __real_sem_destroy(semaphore);
}
}

TEST_F(AAudioRouting, unsupportedAndMissingSymbolsPreserveNormalAudio) {
    for (int api : {28, 36, 37}) {
        platform = Platform{};
        platform.symbol = false;
        Listener listener;
        ticktock::AAudioDevice device(wake, nullptr, ticktock::RoutingSymbols::resolve(api, lookup));
        ASSERT_TRUE(device.open({}, listener).ok);
        ASSERT_TRUE(device.start());
        EXPECT_EQ(api >= 37 ? 1 : 0, platform.lookups);
        EXPECT_EQ(0, platform.registrations);
        auto* stream = platform.streams.back().get();
        float samples[384] = {};
        stream->data(stream, stream->dataUser, samples, 192);
        EXPECT_EQ(1, listener.renders);
        device.close();
        EXPECT_TRUE(stream->closed);
    }
}

TEST_F(AAudioRouting, api37RegistersTheRealDeviceCallbackAndDefersPlatformReads) {
    Listener listener;
    ticktock::AAudioDevice device(wake, nullptr, ticktock::RoutingSymbols::resolve(37, lookup));
    ASSERT_TRUE(device.open({}, listener).ok);
    ASSERT_TRUE(device.start());
    ASSERT_EQ(1, platform.registrations);
    auto* stream = platform.streams.back().get();
    ASSERT_NE(nullptr, stream->routing);
    platform.deviceReads = 0;
    long allocations;
    {
        ticktock::test::CountAllocations count;
        route(stream);
        allocations = count.count();
    }
    EXPECT_EQ(0, allocations);
    EXPECT_EQ(1, listener.routes);
    EXPECT_EQ(1, platform.wakes);
    EXPECT_EQ(1, platform.lookups);
    EXPECT_EQ(0, platform.deviceReads);
    stream->device = 42;
    EXPECT_EQ(42, device.routedDeviceId());
    EXPECT_EQ(1, platform.deviceReads);
}

TEST_F(AAudioRouting, callbacksDuringCloseAndFromAnOldStreamAfterReopenAreIgnored) {
    Listener listener;
    ticktock::AAudioDevice device(wake, nullptr, ticktock::RoutingSymbols::resolve(37, lookup));
    ASSERT_TRUE(device.open({}, listener).ok);
    auto* old = platform.streams.back().get();
    platform.callbackDuringClose = true;
    device.close();
    EXPECT_EQ(0, listener.routes);
    EXPECT_EQ(0, platform.wakes);
    ASSERT_TRUE(device.open({}, listener).ok);
    auto* current = platform.streams.back().get();
    route(old);
    old->error(old, old->errorUser, AAUDIO_ERROR_DISCONNECTED);
    EXPECT_EQ(0, listener.routes);
    EXPECT_EQ(0, listener.errors);
    EXPECT_EQ(0, platform.wakes);
    route(current);
    EXPECT_EQ(1, listener.routes);
    EXPECT_EQ(1, platform.wakes);
}

TEST_F(AAudioRouting, disconnectedStreamCannotPublishMoreRoutingCallbacks) {
    Listener listener;
    ticktock::AAudioDevice device(wake, nullptr, ticktock::RoutingSymbols::resolve(37, lookup));
    ASSERT_TRUE(device.open({}, listener).ok);
    auto* stream = platform.streams.back().get();
    stream->error(stream, stream->errorUser, AAUDIO_ERROR_DISCONNECTED);
    const int wakes = platform.wakes;
    route(stream);
    EXPECT_EQ(1, listener.errors);
    EXPECT_EQ(0, listener.routes);
    EXPECT_EQ(wakes, platform.wakes);
}

TEST_F(AAudioRouting, bridgeRouteWakeMakesTheIdleSemaphoreReady) {
    Bridge bridge;
    bridge.controller.registerTool(ticktock::render::OutputTool::Drone);
    ticktock::control::ControlEvent events[4];
    ASSERT_EQ(1, bridge.controller.drain(events, 4));
    EXPECT_NE(0, sem_trywait(&bridge.wake));
    bridge.controller.onRoutingChanged();
    Bridge::onWake(&bridge);
    EXPECT_EQ(0, sem_trywait(&bridge.wake));
}

TEST_F(AAudioRouting, bridgeClosesTheActualStreamBeforeDestroyingItsWakeSemaphore) {
    platform.watchLifetime = true;
    {
        Bridge bridge;
        bridge.controller.registerTool(ticktock::render::OutputTool::Drone);
        ASSERT_TRUE(bridge.device.isOpen());
    }
    EXPECT_EQ((std::vector<int>{1, 2, 3}), platform.lifetime);
}
