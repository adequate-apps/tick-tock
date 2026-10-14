// SPDX-License-Identifier: GPL-3.0-or-later
//
// The control-thread half of the engine: the registry that opens the stream
// on the first tool and closes it on the last, the park/resume pair the
// route and call policies drive, the buffer tuning on xruns, and the event
// drain that turns render-side beats and device errors into OutputEvents.
// Every public method runs on the Kotlin control thread under one mutex;
// onRender and onError run on the device's threads and take no lock.
#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>

#include "../render/RenderEngine.h"
#include "OutputDevice.h"

namespace ticktock::control {

// ticktock.core.contracts.OutputEvent, in declaration order.
enum class EventKind : std::int32_t { Opened = 0, Closed = 1, Beat = 2, Parked = 3, Resumed = 4, Failed = 5, Counters = 6 };

// ticktock.core.contracts.ParkReason, in declaration order.
enum class ParkReason : std::int32_t { HeadphonesDisconnected = 0, IncomingCall = 1, OutputFailed = 2, Background = 3 };

// One OutputEvent as numbers. The bridge turns it into the Kotlin object:
//   Opened, Resumed  a = sampleRate, b = framesPerBlock, c = deviceId, d = latencyMillis,
//                    tools = Resumed's tool mask (Opened: 0)
//   Beat             a = index, b = beatInBar, c = frame, d = nanos
//   Parked           a = reason, tools = tool mask
//   Failed           a = deviceId, ints = the refused rates
//   Counters         a = lateBeatsDropped, b = underruns
struct ControlEvent {
    EventKind kind = EventKind::Closed;
    std::int64_t a = 0;
    std::int64_t b = 0;
    std::int64_t c = 0;
    std::int64_t d = 0;
    std::uint32_t tools = 0;
    std::int32_t intCount = 0;
    std::int32_t ints[4] = {};
};

struct Diagnostics {
    std::int32_t lateBeatsDropped = 0;
    std::int32_t underruns = 0;
    std::int32_t bufferBursts = 0;
    std::int32_t growthSteps = 0;
    std::int32_t droppedCommands = 0;
    std::int32_t droppedBeats = 0;
    // Events the control-side ring let go of unread; a drain that stalled for seconds.
    std::int32_t evictedEvents = 0;
    std::int64_t framesRendered = 0;
    std::int32_t sampleRate = 0;
    bool streamOpen = false;
    bool parked = false;
};

// Decision 13: start at two bursts, grow one burst per xrun up to four.
constexpr std::int32_t kStartBursts = 2;
constexpr std::int32_t kMaxBursts = 4;

class OutputController final : public DeviceListener {
public:
    explicit OutputController(OutputDevice& device);
    ~OutputController() override;

    // --- OutputCommand, one method per variant ---------------------------
    void registerTool(render::OutputTool tool);
    void unregisterTool(render::OutputTool tool);
    bool voiceOn(std::int32_t slot, std::int32_t midi, render::Voice voice, float gain);
    bool voiceOff(std::int32_t slot);
    bool voiceGain(std::int32_t slot, float gain);
    bool setA4(double hz);
    bool duck(float gain);
    bool metroStart(const render::MetroParams& params);
    bool metroUpdate(const render::MetroParams& params);
    bool metroStop();
    bool mute(render::MuteReason reason, bool active);

    // --- the route and call policies ---------------------------------------
    // Closes the stream and keeps the registered set as the parked set
    // (OUT-03). Nothing restarts until resume() or a Register.
    void park(ParkReason reason);
    // Reopens for the parked set, at whatever rate the device now gives
    // (OUT-05, OUT-13); the next click comes one beat later (MET-09).
    void resume();

    // --- events ------------------------------------------------------------
    // Handles a deferred device error, converts beats, reports counters when
    // they change, then copies up to `max` events out. Returns the count.
    std::int32_t drain(ControlEvent* out, std::int32_t max);
    // True when drain() would return something now.
    bool hasEvents();
    // Audio-thread safe: whether an error waits for drain().
    bool hasPendingError() const { return pendingError_.load(std::memory_order_acquire) != 0; }

    Diagnostics diagnostics();
    // Output latency from the device timestamp, or the buffer length until
    // the timestamp is valid.
    std::int32_t latencyMillis();

    // --- DeviceListener (device threads) -----------------------------------
    void onRender(float* interleaved, std::int32_t frames) override;
    void onError(std::int32_t errorCode) override;

    render::RenderEngine& engine() { return engine_; }

private:
    bool openStream(bool resumed);
    void closeStream();
    void push(const ControlEvent& event);
    std::int64_t frameToNanos(std::int64_t frame);
    std::int32_t latencyLocked();

    OutputDevice& device_;
    render::RenderEngine engine_;
    std::mutex mutex_;

    std::uint32_t registered_ = 0;
    bool parked_ = false;
    bool streamOpen_ = false;
    std::int32_t sampleRate_ = 0;
    std::int32_t framesPerBurst_ = 0;
    std::int32_t deviceId_ = 0;

    // Touched by the audio thread in onRender and read for diagnostics.
    std::atomic<std::int32_t> bursts_{kStartBursts};
    std::atomic<std::int32_t> growthSteps_{0};
    std::atomic<std::int32_t> lastXruns_{0};
    std::atomic<std::int32_t> pendingError_{0};

    std::int32_t reportedLate_ = 0;
    std::int32_t reportedUnderruns_ = 0;

    static constexpr std::int32_t kEventCapacity = 256;
    // Room a drain keeps for the park and the counters it may push after the beats.
    static constexpr std::int32_t kEventsReserved = 2;
    ControlEvent events_[kEventCapacity];
    std::int32_t eventHead_ = 0;
    std::int32_t eventCount_ = 0;
    std::int32_t evictedEvents_ = 0;
};

}  // namespace ticktock::control
