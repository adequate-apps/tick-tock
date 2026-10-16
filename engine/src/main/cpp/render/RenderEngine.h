// SPDX-License-Identifier: GPL-3.0-or-later
//
// Everything that runs inside the audio callback, behind two SPSC queues:
// commands in from the control thread, beat events out. The callback owns
// every piece of mutable DSP state, so no lock exists; the only shared words
// are the queue indices and a few counters, all atomics (OUT-16).
#pragma once

#include <atomic>
#include <cstdint>

#include "BeatGenerator.h"
#include "Clicks.h"
#include "Commands.h"
#include "SpscQueue.h"
#include "Voices.h"

namespace ticktock::render {

constexpr std::uint32_t kCommandCapacity = 256;
constexpr std::uint32_t kBeatCapacity = 1024;
// Blocks longer than this render in slices; the scratch buffer lives in the
// object, never on the heap or the callback's stack.
constexpr std::int32_t kMaxSlice = 4096;
constexpr std::int32_t kMaxBeatsPerSlice = 64;

// POL-26: the full-to-floor duck takes 80 ms in either direction.
constexpr float kDuckFloor = 0.30f;
constexpr double kDuckRampSeconds = 0.080;

class RenderEngine {
public:
    using CommandQueue = SpscQueue<Command, kCommandCapacity>;
    using BeatQueue = SpscQueue<BeatEvent, kBeatCapacity>;
    using DuckQueue = SpscQueue<DuckEvent, kCommandCapacity>;

    CommandQueue& commands() { return commands_; }
    BeatQueue& beats() { return beats_; }
    DuckQueue& ducks() { return ducks_; }

    // Called before the first callback of every stream, old or reopened:
    // the frame clock restarts, voices recompute their increments and the
    // generator converts its anchor (OUT-13, MET-09). Output fades in over
    // the gain ramp so a reopen never pops.
    void onStreamStart(double sampleRate);

    // Fills `frames` interleaved stereo frames. Never allocates, locks or logs.
    void render(float* interleaved, std::int32_t frames);

    double sampleRate() const { return sampleRate_; }
    std::int64_t position() const { return position_.load(std::memory_order_acquire); }
    std::int32_t lateDropped() const { return lateDropped_.load(std::memory_order_acquire); }
    std::int32_t droppedCommands() const { return droppedCommands_.load(std::memory_order_acquire); }
    std::int32_t droppedBeats() const { return droppedBeats_.load(std::memory_order_acquire); }

    // Inspection for the host tests; the device never calls these.
    const VoiceBank& voices() const { return voices_; }
    const ClickPlayer& clicks() const { return clicks_; }
    const BeatGenerator& generator() const { return generator_; }
    bool audible() const { return muteMask_ == 0; }

    // The control side sends a command by pushing into commands(); this is
    // the convenience the bridge uses and counts the drop if the ring is full.
    bool send(const Command& cmd) {
        if (commands_.tryPush(cmd)) return true;
        droppedCommands_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }

private:
    void apply(const Command& cmd);
    void renderSlice(float* interleaved, std::int32_t frames);
    void rampMaster();

    CommandQueue commands_;
    BeatQueue beats_;
    DuckQueue ducks_;
    VoiceBank voices_;
    ClickPlayer clicks_;
    BeatGenerator generator_;
    BeatEvent sliceEvents_[kMaxBeatsPerSlice];
    float mono_[kMaxSlice] = {};

    double sampleRate_ = 48000.0;
    std::atomic<std::int64_t> position_{0};
    std::atomic<std::int32_t> lateDropped_{0};
    std::atomic<std::int32_t> droppedCommands_{0};
    std::atomic<std::int32_t> droppedBeats_{0};

    // Master gain is duck x mute gate x start fade, each ramped per sample.
    std::uint32_t muteMask_ = 0;
    float duck_ = 1.0f;
    float duckTarget_ = 1.0f;
    std::int32_t duckGeneration_ = 0;
    float gate_ = 1.0f;
    float gateTarget_ = 1.0f;
    float rampStep_ = 0.004f;
    float duckRampStep_ = 0.0f;
};

}  // namespace ticktock::render
