// SPDX-License-Identifier: GPL-3.0-or-later
#include "OutputController.h"

#include <chrono>

namespace ticktock::control {

namespace {

using render::Command;
using render::CommandKind;

std::uint32_t bit(render::OutputTool tool) { return 1u << static_cast<std::uint32_t>(tool); }

std::int64_t nowNanos() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

}  // namespace

OutputController::OutputController(OutputDevice& device) : device_(device) {}

OutputController::~OutputController() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (streamOpen_) closeStream();
}

// --- registry ------------------------------------------------------------------

void OutputController::registerTool(render::OutputTool tool) {
    std::lock_guard<std::mutex> lock(mutex_);
    const bool wasEmpty = registered_ == 0;
    registered_ |= bit(tool);
    // A registration while parked opens nothing: resume() is the one way out
    // of a park, and the Kotlin control thread, which owns the route policy,
    // decides when to call it.
    if (wasEmpty && !streamOpen_ && !parked_) {
        if (!openStream(false)) parked_ = true;
    }
}

void OutputController::unregisterTool(render::OutputTool tool) {
    std::lock_guard<std::mutex> lock(mutex_);
    const std::uint32_t mask = bit(tool);
    if ((registered_ & mask) == 0) return;
    registered_ &= ~mask;
    if (registered_ != 0) return;
    if (streamOpen_) closeStream();
    parked_ = false;
    ControlEvent closed;
    closed.kind = EventKind::Closed;
    push(closed);
}

// --- render-side commands -------------------------------------------------------

bool OutputController::voiceOn(std::int32_t slot, std::int32_t midi, render::Voice voice, float gain) {
    Command cmd;
    cmd.kind = CommandKind::VoiceOn;
    cmd.slot = slot;
    cmd.midi = midi;
    cmd.voice = voice;
    cmd.gain = gain;
    return engine_.send(cmd);
}

bool OutputController::voiceOff(std::int32_t slot) {
    Command cmd;
    cmd.kind = CommandKind::VoiceOff;
    cmd.slot = slot;
    return engine_.send(cmd);
}

bool OutputController::voiceGain(std::int32_t slot, float gain) {
    Command cmd;
    cmd.kind = CommandKind::VoiceGain;
    cmd.slot = slot;
    cmd.gain = gain;
    return engine_.send(cmd);
}

bool OutputController::setA4(double hz) {
    Command cmd;
    cmd.kind = CommandKind::SetA4;
    cmd.a4 = hz;
    return engine_.send(cmd);
}

bool OutputController::duck(float gain, std::int32_t generation) {
    Command cmd;
    cmd.kind = CommandKind::Duck;
    cmd.gain = gain;
    cmd.generation = generation;
    return engine_.send(cmd);
}

bool OutputController::metroStart(const render::MetroParams& params) {
    Command cmd;
    cmd.kind = CommandKind::MetroStart;
    cmd.metro = params;
    return engine_.send(cmd);
}

bool OutputController::metroUpdate(const render::MetroParams& params) {
    Command cmd;
    cmd.kind = CommandKind::MetroUpdate;
    cmd.metro = params;
    return engine_.send(cmd);
}

bool OutputController::metroStop() {
    Command cmd;
    cmd.kind = CommandKind::MetroStop;
    return engine_.send(cmd);
}

bool OutputController::mute(render::MuteReason reason, bool active) {
    Command cmd;
    cmd.kind = CommandKind::Mute;
    cmd.muteReason = reason;
    cmd.active = active;
    return engine_.send(cmd);
}

// --- park and resume ------------------------------------------------------------

void OutputController::park(ParkReason reason) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (registered_ == 0 || parked_) return;
    if (streamOpen_) closeStream();
    parked_ = true;
    ControlEvent parked;
    parked.kind = EventKind::Parked;
    parked.a = static_cast<std::int64_t>(reason);
    parked.tools = registered_;
    push(parked);
}

void OutputController::resume() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!parked_ || registered_ == 0 || streamOpen_) return;
    if (openStream(true)) parked_ = false;
}

// --- the stream -----------------------------------------------------------------

bool OutputController::openStream(bool resumed) {
    // The device chooses the rate; the two named rates are the fallback and
    // the list OUT-17 reports when every one is refused.
    const std::int32_t rates[] = {0, 48000, 44100};
    ControlEvent failed;
    failed.kind = EventKind::Failed;
    DeviceOpen opened;
    for (std::int32_t rate : rates) {
        DeviceRequest request;
        request.sampleRate = rate;
        opened = device_.open(request, *this);
        if (opened.ok) break;
        if (rate != 0 && failed.intCount < 4) failed.ints[failed.intCount++] = rate;
        failed.a = opened.deviceId;
    }
    if (!opened.ok) {
        push(failed);
        return false;
    }

    sampleRate_ = opened.sampleRate;
    framesPerBurst_ = opened.framesPerBurst;
    deviceId_ = opened.deviceId;
    bursts_.store(kStartBursts, std::memory_order_relaxed);
    lastXruns_.store(0, std::memory_order_relaxed);
    pendingError_.store(0, std::memory_order_relaxed);
    device_.setBufferBursts(kStartBursts);
    engine_.onStreamStart(sampleRate_);
    if (!device_.start()) {
        device_.close();
        failed.a = opened.deviceId;
        push(failed);
        return false;
    }
    streamOpen_ = true;

    ControlEvent event;
    event.kind = resumed ? EventKind::Resumed : EventKind::Opened;
    event.a = sampleRate_;
    event.b = framesPerBurst_;
    event.c = deviceId_;
    event.d = latencyLocked();
    event.tools = resumed ? registered_ : 0;
    push(event);
    return true;
}

void OutputController::closeStream() {
    device_.stop();
    device_.close();
    streamOpen_ = false;
}

// --- device threads -------------------------------------------------------------

void OutputController::onRender(float* interleaved, std::int32_t frames) {
    engine_.render(interleaved, frames);
    // Decision 13: one more burst per xrun, up to four, each step counted
    // for Diagnostics. Reading the count is a local read on a shared stream.
    // The device answers with the size it set, clamped to its capacity or 0
    // on an error; only a size that grew is a step (SHM-02).
    const std::int32_t xruns = device_.xrunCount();
    if (xruns > lastXruns_.load(std::memory_order_relaxed)) {
        lastXruns_.store(xruns, std::memory_order_relaxed);
        const std::int32_t bursts = bursts_.load(std::memory_order_relaxed);
        if (bursts < kMaxBursts) {
            const std::int32_t grantedFrames = device_.setBufferBursts(bursts + 1);
            const std::int32_t granted = framesPerBurst_ > 0 ? grantedFrames / framesPerBurst_ : 0;
            if (granted > bursts) {
                bursts_.store(granted < kMaxBursts ? granted : kMaxBursts, std::memory_order_relaxed);
                growthSteps_.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }
}

void OutputController::onError(std::int32_t errorCode) {
    // Only remembered here; drain() closes the stream on the control thread.
    pendingError_.store(errorCode == 0 ? -1 : errorCode, std::memory_order_release);
}

// --- events ---------------------------------------------------------------------

void OutputController::push(const ControlEvent& event) {
    if (eventCount_ == kEventCapacity) {
        // The ring is full because nobody drained for seconds; the oldest
        // event is the least useful one by then, and the loss is counted.
        eventHead_ = (eventHead_ + 1) % kEventCapacity;
        --eventCount_;
        ++evictedEvents_;
    }
    events_[(eventHead_ + eventCount_) % kEventCapacity] = event;
    ++eventCount_;
}

std::int64_t OutputController::frameToNanos(std::int64_t frame) {
    std::int64_t tsFrames = 0;
    std::int64_t tsNanos = 0;
    if (sampleRate_ > 0 && device_.timestamp(tsFrames, tsNanos)) {
        const double seconds = static_cast<double>(frame - tsFrames) / sampleRate_;
        return tsNanos + static_cast<std::int64_t>(seconds * 1e9);
    }
    return nowNanos();
}

std::int32_t OutputController::latencyLocked() {
    if (!streamOpen_ || sampleRate_ <= 0) return 0;
    std::int64_t tsFrames = 0;
    std::int64_t tsNanos = 0;
    if (device_.timestamp(tsFrames, tsNanos)) {
        // Frames written but not yet presented, corrected for the time since
        // the timestamp was taken, is the latency the player hears.
        const double elapsed = static_cast<double>(nowNanos() - tsNanos) / 1e9;
        const double presented = static_cast<double>(tsFrames) + elapsed * sampleRate_;
        const double pending = static_cast<double>(device_.framesWritten()) - presented;
        if (pending > 0.0) return static_cast<std::int32_t>(pending * 1000.0 / sampleRate_ + 0.5);
    }
    const std::int32_t frames = bursts_.load(std::memory_order_relaxed) * framesPerBurst_;
    return static_cast<std::int32_t>(frames * 1000.0 / sampleRate_ + 0.5);
}

std::int32_t OutputController::latencyMillis() {
    std::lock_guard<std::mutex> lock(mutex_);
    return latencyLocked();
}

bool OutputController::hasEvents() {
    std::lock_guard<std::mutex> lock(mutex_);
    return eventCount_ > 0 || engine_.beats().size() > 0 || engine_.ducks().size() > 0 || pendingError_.load(std::memory_order_acquire) != 0;
}

std::int32_t OutputController::drain(ControlEvent* out, std::int32_t max) {
    std::lock_guard<std::mutex> lock(mutex_);

    render::DuckEvent duck;
    while (eventCount_ < kEventCapacity - kEventsReserved && engine_.ducks().tryPop(duck)) {
        ControlEvent event;
        event.kind = EventKind::DuckSettled;
        event.a = duck.generation;
        event.c = duck.frame;
        // Without a timestamp, wait a full output buffer beyond the drain.
        std::int64_t position = 0, nanos = 0;
        event.d = device_.timestamp(position, nanos)
            ? nanos + static_cast<std::int64_t>((duck.frame - position) * 1e9 / sampleRate_)
            : nowNanos() + static_cast<std::int64_t>(latencyLocked()) * 1000000;
        push(event);
    }

    // Beats move over only while the ring has room for them and for what
    // follows below; the rest wait in the render queue, whose own overflow
    // is the droppedBeats figure.
    render::BeatEvent beat;
    while (eventCount_ < kEventCapacity - kEventsReserved && engine_.beats().tryPop(beat)) {
        ControlEvent event;
        event.kind = EventKind::Beat;
        event.a = beat.index;
        event.b = beat.beatInBar;
        event.intCount = 4;
        event.ints[0] = static_cast<std::int32_t>(static_cast<std::uint64_t>(beat.bar) & 0xffffffffu);
        event.ints[1] = static_cast<std::int32_t>(static_cast<std::uint64_t>(beat.bar) >> 32);
        event.ints[2] = (beat.audible ? 1 : 0) | (beat.silentBar ? 2 : 0);
        event.ints[3] = beat.generation;
        event.c = beat.frame;
        event.d = frameToNanos(beat.frame);
        push(event);
    }

    // The beats above sounded before the stream died, so they go out first.
    const std::int32_t error = pendingError_.exchange(0, std::memory_order_acq_rel);
    if (error != 0 && streamOpen_) {
        // The AAudio guide: stop and close on any error, from another thread.
        // A disconnect is a route loss; anything else is the output failing.
        closeStream();
        parked_ = true;
        ControlEvent parked;
        parked.kind = EventKind::Parked;
        parked.a = static_cast<std::int64_t>(error == kErrorDisconnected ? ParkReason::HeadphonesDisconnected
                                                                          : ParkReason::OutputFailed);
        parked.tools = registered_;
        push(parked);
    }

    const std::int32_t late = engine_.lateDropped();
    const std::int32_t underruns = lastXruns_.load(std::memory_order_relaxed);
    if (late != reportedLate_ || underruns != reportedUnderruns_) {
        reportedLate_ = late;
        reportedUnderruns_ = underruns;
        ControlEvent counters;
        counters.kind = EventKind::Counters;
        counters.a = late;
        counters.b = underruns;
        push(counters);
    }

    std::int32_t n = 0;
    while (n < max && eventCount_ > 0) {
        out[n++] = events_[eventHead_];
        eventHead_ = (eventHead_ + 1) % kEventCapacity;
        --eventCount_;
    }
    return n;
}

Diagnostics OutputController::diagnostics() {
    std::lock_guard<std::mutex> lock(mutex_);
    Diagnostics d;
    d.lateBeatsDropped = engine_.lateDropped();
    d.underruns = lastXruns_.load(std::memory_order_relaxed);
    d.bufferBursts = bursts_.load(std::memory_order_relaxed);
    d.growthSteps = growthSteps_.load(std::memory_order_relaxed);
    d.droppedCommands = engine_.droppedCommands();
    d.droppedBeats = engine_.droppedBeats();
    d.evictedEvents = evictedEvents_;
    d.framesRendered = engine_.position();
    d.sampleRate = sampleRate_;
    d.streamOpen = streamOpen_;
    d.parked = parked_;
    return d;
}

}  // namespace ticktock::control
