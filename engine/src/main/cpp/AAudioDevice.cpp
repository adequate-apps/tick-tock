// SPDX-License-Identifier: GPL-3.0-or-later
#include "AAudioDevice.h"

#include <time.h>
#include <android/api-level.h>
#include <dlfcn.h>
#include <limits>
#include <mutex>
#include <thread>

namespace ticktock {

static_assert(std::atomic<AAudioStream*>::is_always_lock_free, "callback identity must be lock-free");
static_assert(std::atomic<AAudioDevice*>::is_always_lock_free, "callback owner must be lock-free");
static_assert(std::atomic<std::uintptr_t>::is_always_lock_free, "callback generation must be lock-free");
static_assert(std::atomic<std::uint32_t>::is_always_lock_free, "callback readers must be lock-free");

namespace {
constexpr unsigned kSlotBits = 6;
constexpr std::uintptr_t kCallbackCapacity = std::uintptr_t{1} << kSlotBits;
constexpr std::uintptr_t kSlotMask = kCallbackCapacity - 1;
constexpr std::uintptr_t kLastGeneration = std::numeric_limits<std::uintptr_t>::max() >> kSlotBits;

struct CallbackSlot {
    std::atomic<AAudioDevice*> owner{nullptr};
    std::atomic<std::uintptr_t> generation{0};
    std::atomic<std::uint32_t> readers{0};
    bool claimed = false;  // Control threads only, under callbackSlotsMutex.
};

// Never reclaim slot storage: a framework task may pause before invoking us,
// then resume after the stream and owner have both been destroyed/replaced.
// Userdata encodes an index/generation, never an address to dereference.
CallbackSlot callbackSlots[kCallbackCapacity];
std::mutex callbackSlotsMutex;

void* claimCallbackToken(AAudioDevice* owner) {
    std::lock_guard<std::mutex> lock(callbackSlotsMutex);
    for (std::uintptr_t index = 0; index < kCallbackCapacity; ++index) {
        auto& slot = callbackSlots[index];
        const auto previous = slot.generation.load(std::memory_order_seq_cst);
        // A generation at its limit permanently retires this bounded slot.
        if (slot.claimed || previous == kLastGeneration) continue;
        slot.claimed = true;
        const auto generation = previous + 1;
        slot.generation.store(generation, std::memory_order_seq_cst);
        slot.owner.store(owner, std::memory_order_seq_cst);
        return reinterpret_cast<void*>((generation << kSlotBits) | index);
    }
    return nullptr;
}

void detachCallbackToken(void* token) {
    const auto value = reinterpret_cast<std::uintptr_t>(token);
    callbackSlots[value & kSlotMask].owner.store(nullptr, std::memory_order_seq_cst);
}

void drainCallbackToken(void* token) {
    const auto value = reinterpret_cast<std::uintptr_t>(token);
    auto& slot = callbackSlots[value & kSlotMask];
    // Control thread only. Callbacks never wait for control or for each other.
    while (slot.readers.load(std::memory_order_seq_cst) != 0) std::this_thread::yield();
    std::lock_guard<std::mutex> lock(callbackSlotsMutex);
    slot.claimed = false;
}

struct CallbackLease {
    explicit CallbackLease(void* token);
    ~CallbackLease();
    CallbackLease(const CallbackLease&) = delete;
    CallbackLease& operator=(const CallbackLease&) = delete;
    CallbackSlot* slot = nullptr;
    AAudioDevice* owner = nullptr;
};

CallbackLease::CallbackLease(void* token) {
    const auto value = reinterpret_cast<std::uintptr_t>(token);
    const auto generation = value >> kSlotBits;
    if (generation == 0) return;
    slot = &callbackSlots[value & kSlotMask];
    slot->readers.fetch_add(1, std::memory_order_seq_cst);
    // Preserve this exact order: reader increment, owner load, generation load.
    // A reader arriving after detach/drain can overlap reuse; seeing a new
    // owner must also see its new generation, so an old token cannot accept it.
    auto* candidate = slot->owner.load(std::memory_order_seq_cst);
    if (slot->generation.load(std::memory_order_seq_cst) == generation) owner = candidate;
}

CallbackLease::~CallbackLease() {
    if (slot != nullptr) slot->readers.fetch_sub(1, std::memory_order_seq_cst);
}
}  // namespace

RoutingSymbols RoutingSymbols::system() {
    return resolve(android_get_device_api_level(), [](const char* name) {
        // libaaudio is already a required link dependency; no library is loaded here.
        return dlsym(RTLD_DEFAULT, name);
    });
}

static_assert(AAUDIO_ERROR_DISCONNECTED == control::kErrorDisconnected,
              "the controller's disconnect code must match AAudio's");

control::DeviceOpen AAudioDevice::open(const control::DeviceRequest& request, control::DeviceListener& listener) {
    control::DeviceOpen result;
    close();
    listener_ = &listener;
    callbackToken_ = claimCallbackToken(this);
    if (callbackToken_ == nullptr) {
        listener_ = nullptr;
        result.errorCode = AAUDIO_ERROR_UNAVAILABLE;
        return result;
    }

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t rc = AAudio_createStreamBuilder(&builder);
    if (rc != AAUDIO_OK || builder == nullptr) {
        close();
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
    AAudioStreamBuilder_setDataCallback(builder, &AAudioDevice::onData, callbackToken_);
    AAudioStreamBuilder_setErrorCallback(builder, &AAudioDevice::onError, callbackToken_);

    if (routing_.setCallback != nullptr) routing_.setCallback(builder, &AAudioDevice::onRoutingChanged, callbackToken_);

    rc = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    if (rc != AAUDIO_OK || stream_ == nullptr) {
        close();
        result.errorCode = rc;
        return result;
    }

    activeStream_.store(stream_, std::memory_order_release);
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
    // Detach before stopping delivery. Even a task paused before callback entry
    // can only use inert slot storage after this owner has been retired.
    if (callbackToken_ != nullptr) detachCallbackToken(callbackToken_);
    activeStream_.store(nullptr, std::memory_order_release);
    // Always from the control thread, never from the error callback.
    if (stream_ != nullptr) AAudioStream_close(stream_);
    stream_ = nullptr;
    // Platform close does not join an already running routing task. Keep the
    // listener and wake context alive until every acquired lease has returned.
    if (callbackToken_ != nullptr) drainCallbackToken(callbackToken_);
    callbackToken_ = nullptr;
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

std::int32_t AAudioDevice::routedDeviceId() {
    return stream_ == nullptr ? 0 : AAudioStream_getDeviceId(stream_);
}

aaudio_data_callback_result_t AAudioDevice::onData(AAudioStream* stream, void* userData, void* audioData, int32_t numFrames) {
    CallbackLease lease(userData);
    auto* self = lease.owner;
    if (self == nullptr) return AAUDIO_CALLBACK_RESULT_CONTINUE;
    if (self->activeStream_.load(std::memory_order_acquire) != stream) return AAUDIO_CALLBACK_RESULT_CONTINUE;
    if (self->listener_ != nullptr) self->listener_->onRender(static_cast<float*>(audioData), numFrames);
    if (self->wake_ != nullptr) self->wake_(self->context_);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioDevice::onError(AAudioStream* stream, void* userData, aaudio_result_t error) {
    CallbackLease lease(userData);
    auto* self = lease.owner;
    if (self == nullptr) return;
    if (!self->activeStream_.compare_exchange_strong(stream, nullptr, std::memory_order_acq_rel)) return;
    if (self->listener_ != nullptr) self->listener_->onError(error);
    if (self->wake_ != nullptr) self->wake_(self->context_);
}

void AAudioDevice::onRoutingChanged(AAudioStream* stream, void* userData, const int32_t*, int32_t) {
    CallbackLease lease(userData);
    auto* self = lease.owner;
    if (self == nullptr) return;
    if (self->activeStream_.load(std::memory_order_acquire) != stream) return;
    // The framework owns the temporary device list. Read current facts on
    // the control thread; here only invalidate presentation and wake it.
    if (self->listener_ != nullptr) self->listener_->onRoutingChanged();
    if (self->wake_ != nullptr) self->wake_(self->context_);
}

}  // namespace ticktock
