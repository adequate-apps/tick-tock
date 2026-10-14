// SPDX-License-Identifier: GPL-3.0-or-later
//
// The sample-accurate beat generator. It lives inside the render callback and
// holds exactly one next beat, so a dispatched beat has no path by which it
// could sound twice (MET-05). Onsets follow MET-01:
//
//     onset(k) = anchorFrame + round((k - anchorIndex) * sr * 60 / bpm)
//
// A tempo change re-anchors on the frame the last beat actually sounded
// (MET-06); a metre change resets the bar (MET-07); a stream restart places
// the next beat one period after the first frame of the new stream (MET-09).
// Gap bars silence whole bars while the beat events keep coming (GAP-01), a
// muted beat keeps its subdivisions audible (GAP-03), and an onset more than
// 2 ms in the past is dropped and counted instead of played late (MET-04).
#pragma once

#include <cstdint>

#include "Clicks.h"
#include "Commands.h"

namespace ticktock::render {

// Gain multipliers per accent level: ACCENT, NORMAL, SOFT, MUTE.
constexpr float kAccentGain[4] = {1.0f, 0.7f, 0.4f, 0.0f};
// Subdivision clicks sit at the SOFT level under the beat clicks.
constexpr float kSubdivisionGain = 0.4f;
// A click whose onset is further in the past than this is late (MET-04).
constexpr double kLateSeconds = 0.002;

class BeatGenerator {
public:
    void prepare(double sampleRate);

    // Starts at `frame` so the first beat lands on the first frame rendered
    // after the command arrived.
    void start(const MetroParams& params, std::int64_t frame);

    // Live edit (MET-05, MET-06, MET-07).
    void update(const MetroParams& params);

    void stop() { running_ = false; }

    // The output came back, possibly at another rate: convert the anchor so
    // the next click comes one beat after the first frame of the new stream.
    void onStreamStart(double sampleRate, std::int64_t frame);

    // Places every onset inside [blockStart, blockStart + frames) on the click
    // player and writes a BeatEvent per beat (never per subdivision). Returns
    // the number of events written.
    std::int32_t render(std::int64_t blockStart, std::int32_t frames, ClickPlayer& clicks, BeatEvent* events,
                        std::int32_t maxEvents);

    bool running() const { return running_; }
    std::int32_t lateDropped() const { return lateDropped_; }
    std::int64_t nextIndex() const { return nextIndex_; }

    // The frame where beat `index` falls under the current anchor.
    std::int64_t onsetOf(std::int64_t index) const;

    // Frames per beat; the compound pulse puts three beats under one tempo
    // pulse, so 6/8 at 60 means the dotted quarter at 60.
    double periodFrames() const;

private:
    void placeBeat(std::int64_t frame);
    std::int64_t nextSubOnset() const;

    MetroParams params_{};
    double sampleRate_ = 48000.0;
    bool running_ = false;

    std::int64_t anchorFrame_ = 0;
    std::int64_t anchorIndex_ = 0;
    std::int64_t nextIndex_ = 0;
    std::int32_t beatInBar_ = 0;
    std::int64_t bar_ = 0;

    bool haveLast_ = false;
    std::int64_t lastFrame_ = 0;
    std::int64_t lastIndex_ = 0;
    // Whether the bar the last beat fell in sounds, kept for its subdivisions
    // after the bar counter has moved on.
    bool lastBarSounds_ = true;
    // Subdivision clicks follow the last beat: the next one is number sub_ of
    // params_.subdivision; equal means none left before the next beat.
    std::int32_t sub_ = 1;

    std::int32_t lateDropped_ = 0;
};

}  // namespace ticktock::render
