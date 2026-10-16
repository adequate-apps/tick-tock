// SPDX-License-Identifier: GPL-3.0-or-later
// The actual platform device and bridge run against scriptable platform entry points.
#include "AAudioDevice.h"
#include "RoutingSymbols.h"
#include "support/AllocCounter.h"
#include <gtest/gtest.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <thread>
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
// Only test code waits. Production callbacks must remain lock-free and bounded.
class Signal {
public:
    void set() {
        std::lock_guard<std::mutex> lock(mutex_);
        set_ = true;
        changed_.notify_all();
    }
    bool wait(std::chrono::milliseconds timeout = std::chrono::seconds(5)) {
        std::unique_lock<std::mutex> lock(mutex_);
        return changed_.wait_for(lock, timeout, [this] { return set_; });
    }
private:
    std::mutex mutex_;
    std::condition_variable changed_;
    bool set_ = false;
};

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
    // Model a framework task that outlives platform close and stream-address reuse.
    Signal* closeReached = nullptr;
    AAudioStream* reuseClosedStream = nullptr;
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
struct SavedCallbacks {
    AAudioStream* stream;
    AAudioStream_dataCallback data;
    AAudioStream_errorCallback error;
    RoutingCallback routing;
    void* dataUser;
    void* errorUser;
    void* routeUser;

    explicit SavedCallbacks(AAudioStream* current)
        : stream(current), data(current->data), error(current->error), routing(current->routing),
          dataUser(current->dataUser), errorUser(current->errorUser), routeUser(current->routeUser) {}

    void callRoute() const {
        const std::int32_t ids[] = {42};
        routing(stream, routeUser, ids, 1);
    }
    void callData() const {
        float samples[384] = {};
        data(stream, dataUser, samples, 192);
    }
    void callError() const { error(stream, errorUser, AAUDIO_ERROR_DISCONNECTED); }
};

// Explicit placement reconstruction makes owner-address reuse defined behavior.
struct DeviceStorage {
    alignas(ticktock::AAudioDevice) unsigned char bytes[sizeof(ticktock::AAudioDevice)] = {};
    ticktock::AAudioDevice* value = nullptr;
    ~DeviceStorage() { destroy(); }
    ticktock::AAudioDevice& construct(ticktock::AAudioDevice::WakeFn callback = wake, void* context = nullptr) {
        value = new (bytes) ticktock::AAudioDevice(callback, context, ticktock::RoutingSymbols::resolve(37, lookup));
        return *value;
    }
    void destroy() {
        if (value != nullptr) value->~AAudioDevice();
        value = nullptr;
    }
};

struct WakeLifetime {
    std::atomic<bool> retired{false};
    std::atomic<int> calls{0};
    std::atomic<int> afterRetirement{0};
    static void wake(void* context) {
        auto& state = *static_cast<WakeLifetime*>(context);
        state.calls.fetch_add(1);
        if (state.retired.load()) state.afterRetirement.fetch_add(1);
    }
};

struct HeldRoutingListener : Listener {
    Signal entered, release;
    std::atomic<bool> timedOut{false};
    void onRoutingChanged() override {
        entered.set();
        if (!release.wait()) timedOut.store(true);
        ++routes;
    }
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
    if (platform.reuseClosedStream != nullptr) {
        auto* reused = platform.reuseClosedStream;
        platform.reuseClosedStream = nullptr;
        EXPECT_TRUE(reused->closed);
        *reused = AAudioStream{};
        static_cast<AAudioStreamBuilderStruct&>(*reused) = *b;
        *out = reused;
        return 0;
    }
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
    if (platform.closeReached != nullptr) platform.closeReached->set();
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

TEST_F(AAudioRouting, closeWaitsForAnEnteredRoutingCallbackBeforeRetiringItsWakeContext) {
    HeldRoutingListener listener;
    WakeLifetime lifetime;
    Signal platformClosed, closeReturned;
    platform.closeReached = &platformClosed;
    ticktock::AAudioDevice device(WakeLifetime::wake, &lifetime, ticktock::RoutingSymbols::resolve(37, lookup));
    ASSERT_TRUE(device.open({}, listener).ok);
    const SavedCallbacks callbacks(platform.streams.back().get());
    ASSERT_NE(nullptr, callbacks.routing);
    std::thread callback([&] { callbacks.callRoute(); });
    const bool entered = listener.entered.wait();
    if (!entered) {
        listener.release.set();
        callback.join();
        FAIL() << "routing callback did not reach the test listener";
    }
    std::thread closer([&] {
        device.close();
        // Models retiring the Bridge wake semaphore/context immediately after close.
        lifetime.retired.store(true);
        closeReturned.set();
    });
    const bool reachedPlatform = platformClosed.wait();
    const bool retiredWhileEntered = closeReturned.wait(std::chrono::milliseconds(100));
    listener.release.set();
    callback.join();
    closer.join();
    platform.closeReached = nullptr;
    EXPECT_TRUE(reachedPlatform) << "close must first stop framework delivery, then drain acquired callbacks";
    EXPECT_FALSE(retiredWhileEntered) << "platform close does not join the routing executor's running task";
    EXPECT_FALSE(listener.timedOut.load());
    EXPECT_EQ(1, listener.routes);
    EXPECT_EQ(1, lifetime.calls.load());
    EXPECT_EQ(0, lifetime.afterRetirement.load()) << "wake ran after its owner was allowed to retire";
}

TEST_F(AAudioRouting, disconnectedCloseAlsoWaitsForAnAlreadyEnteredRoutingCallback) {
    HeldRoutingListener listener;
    WakeLifetime lifetime;
    Signal platformClosed, closeReturned;
    platform.closeReached = &platformClosed;
    ticktock::AAudioDevice device(WakeLifetime::wake, &lifetime, ticktock::RoutingSymbols::resolve(37, lookup));
    ASSERT_TRUE(device.open({}, listener).ok);
    const SavedCallbacks callbacks(platform.streams.back().get());
    ASSERT_NE(nullptr, callbacks.routing);
    std::thread callback([&] { callbacks.callRoute(); });
    const bool entered = listener.entered.wait();
    if (!entered) {
        listener.release.set();
        callback.join();
        FAIL() << "routing callback did not reach the test listener";
    }
    callbacks.callError();
    std::thread closer([&] {
        device.close();
        lifetime.retired.store(true);
        closeReturned.set();
    });
    const bool reachedPlatform = platformClosed.wait();
    const bool retiredWhileEntered = closeReturned.wait(std::chrono::milliseconds(100));
    listener.release.set();
    callback.join();
    closer.join();
    platform.closeReached = nullptr;
    EXPECT_TRUE(reachedPlatform);
    EXPECT_FALSE(retiredWhileEntered);
    EXPECT_FALSE(listener.timedOut.load());
    EXPECT_EQ(1, listener.errors);
    EXPECT_EQ(0, lifetime.afterRetirement.load());
}

TEST_F(AAudioRouting, callbacksStartingAfterOwnerDestructionUseOnlyAnInertToken) {
    Listener listener;
    DeviceStorage storage;
    auto& device = storage.construct();
    ASSERT_TRUE(device.open({}, listener).ok);
    const SavedCallbacks delayed(platform.streams.back().get());
    ASSERT_NE(nullptr, delayed.routing);
    // Fail safely on the old raw-owner implementation instead of deliberately invoking UB.
    ASSERT_NE(static_cast<void*>(&device), delayed.routeUser) << "routing userdata must outlive its owner";
    ASSERT_NE(static_cast<void*>(&device), delayed.dataUser) << "all callbacks must use the lifetime token";
    ASSERT_NE(static_cast<void*>(&device), delayed.errorUser);
    storage.destroy();
    std::memset(storage.bytes, 0xa5, sizeof(storage.bytes));
    const int wakes = platform.wakes;
    delayed.callRoute();
    delayed.callData();
    delayed.callError();
    EXPECT_EQ(0, listener.routes);
    EXPECT_EQ(0, listener.renders);
    EXPECT_EQ(0, listener.errors);
    EXPECT_EQ(wakes, platform.wakes);
}

TEST_F(AAudioRouting, oldTokensCannotReachAReplacementAtTheSameOwnerAndStreamAddresses) {
    Listener oldListener, freshListener;
    DeviceStorage storage;
    auto& old = storage.construct();
    const void* ownerAddress = &old;
    ASSERT_TRUE(old.open({}, oldListener).ok);
    const SavedCallbacks delayed(platform.streams.back().get());
    ASSERT_NE(nullptr, delayed.routing);
    storage.destroy();
    platform.reuseClosedStream = delayed.stream;
    auto& replacement = storage.construct();
    ASSERT_EQ(ownerAddress, static_cast<void*>(&replacement));
    ASSERT_TRUE(replacement.open({}, freshListener).ok);
    const SavedCallbacks fresh(platform.streams.back().get());
    ASSERT_EQ(delayed.stream, fresh.stream) << "exercise the stream-pointer ABA, too";
    EXPECT_NE(delayed.routeUser, fresh.routeUser) << "slot reuse must change the generation token";
    const int wakes = platform.wakes;
    delayed.callRoute();
    delayed.callData();
    delayed.callError();
    EXPECT_EQ(0, oldListener.routes);
    EXPECT_EQ(0, freshListener.routes);
    EXPECT_EQ(0, freshListener.renders);
    EXPECT_EQ(0, freshListener.errors);
    EXPECT_EQ(wakes, platform.wakes);
    fresh.callRoute();
    fresh.callData();
    fresh.callError();
    EXPECT_EQ(1, freshListener.routes);
    EXPECT_EQ(1, freshListener.renders);
    EXPECT_EQ(1, freshListener.errors);
    EXPECT_EQ(wakes + 3, platform.wakes);
}

TEST_F(AAudioRouting, boundedTokenCapacityRefusesExhaustionAndReusesOnlyReleasedSlots) {
    // The agreed process-wide budget is fixed; exhaustion must not silently disable routing.
    constexpr int capacity = 64;
    Listener listener;
    std::vector<std::unique_ptr<ticktock::AAudioDevice>> devices;
    for (int i = 0; i < capacity; ++i) {
        auto device = std::make_unique<ticktock::AAudioDevice>(wake, nullptr, ticktock::RoutingSymbols::resolve(37, lookup));
        ASSERT_TRUE(device->open({}, listener).ok) << i;
        devices.push_back(std::move(device));
    }
    const SavedCallbacks oldest(platform.streams.front().get());
    ticktock::AAudioDevice overflow(wake, nullptr, ticktock::RoutingSymbols::resolve(37, lookup));
    EXPECT_FALSE(overflow.open({}, listener).ok) << "callback registry must fail closed at its bounded capacity";
    overflow.close();
    const int wakes = platform.wakes;
    oldest.callRoute();
    EXPECT_EQ(1, listener.routes) << "an exhausted open must not steal a live slot";
    EXPECT_EQ(wakes + 1, platform.wakes);
    devices.front()->close();
    platform.reuseClosedStream = oldest.stream;
    ASSERT_TRUE(overflow.open({}, listener).ok) << "a drained slot must be reusable";
    const SavedCallbacks replacement(oldest.stream);
    EXPECT_NE(oldest.routeUser, replacement.routeUser);
    const int routes = listener.routes;
    oldest.callRoute();
    EXPECT_EQ(routes, listener.routes);
    replacement.callRoute();
    EXPECT_EQ(routes + 1, listener.routes);
}

TEST_F(AAudioRouting, repeatedOpenCloseReusesBoundedStorageWithoutReusingAnyStaleToken) {
    Listener listener;
    ticktock::AAudioDevice device(wake, nullptr, ticktock::RoutingSymbols::resolve(37, lookup));
    std::vector<void*> retiredTokens;
    for (int i = 0; i < 256; ++i) {
        ASSERT_TRUE(device.open({}, listener).ok) << "released slots must not leak across opens: " << i;
        const SavedCallbacks callbacks(platform.streams.back().get());
        EXPECT_TRUE(std::find(retiredTokens.begin(), retiredTokens.end(), callbacks.routeUser) == retiredTokens.end())
            << "a stale token was recycled";
        retiredTokens.push_back(callbacks.routeUser);
        device.close();
    }
}
