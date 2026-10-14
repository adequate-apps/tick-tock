// SPDX-License-Identifier: GPL-3.0-or-later
#include "BeatGenerator.h"

#include <cmath>

namespace ticktock::render {

namespace {

std::int32_t clampInt(std::int32_t v, std::int32_t lo, std::int32_t hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// The Kotlin side validates MetroSetup; clamping here means a corrupt struct
// can at worst sound wrong, never index out of range.
MetroParams sanitised(const MetroParams& in) {
    MetroParams p = in;
    p.bpm = clampInt(p.bpm, kBpmMin, kBpmMax);
    p.beatsPerBar = clampInt(p.beatsPerBar, 1, kBeatsMax);
    p.subdivision = clampInt(p.subdivision, 1, kSubdivisionMax);
    p.barsOn = clampInt(p.barsOn, 1, kGapBarsMax);
    p.barsOff = clampInt(p.barsOff, 0, kGapBarsMax);
    p.gain = p.gain < 0.0f ? 0.0f : (p.gain > 1.0f ? 1.0f : p.gain);
    for (Accent& a : p.accents) {
        const std::int32_t level = static_cast<std::int32_t>(a);
        if (level < 0 || level > 3) a = Accent::Normal;
    }
    return p;
}

}  // namespace

void BeatGenerator::prepare(double sampleRate) {
    sampleRate_ = sampleRate > 0.0 ? sampleRate : 48000.0;
}

double BeatGenerator::periodFrames() const {
    const double pulseDivisor = params_.compoundPulse ? 3.0 : 1.0;
    return sampleRate_ * 60.0 / (params_.bpm * pulseDivisor);
}

std::int64_t BeatGenerator::onsetOf(std::int64_t index) const {
    const double pulseDivisor = params_.compoundPulse ? 3.0 : 1.0;
    const double n = static_cast<double>(index - anchorIndex_);
    return anchorFrame_ + std::llround(n * sampleRate_ * 60.0 / (params_.bpm * pulseDivisor));
}

void BeatGenerator::start(const MetroParams& params, std::int64_t frame) {
    params_ = sanitised(params);
    running_ = true;
    anchorFrame_ = frame;
    anchorIndex_ = 0;
    nextIndex_ = 0;
    beatInBar_ = 0;
    bar_ = 0;
    haveLast_ = false;
    lastFrame_ = frame;
    lastIndex_ = -1;
    sub_ = params_.subdivision;
}

void BeatGenerator::update(const MetroParams& params, std::int64_t frame) {
    const MetroParams next = sanitised(params);
    const bool tempoChanged = next.bpm != params_.bpm || next.compoundPulse != params_.compoundPulse;
    const bool metreChanged = next.beatsPerBar != params_.beatsPerBar;
    params_ = next;

    if (tempoChanged && haveLast_) {
        // MET-06: the new grid grows from the frame the last beat sounded,
        // never from where the old grid said it should have been.
        anchorFrame_ = lastFrame_;
        anchorIndex_ = lastIndex_;
    }
    if (metreChanged) {
        // MET-07: the next beat is a downbeat; a bar cut short still counts
        // as a bar for the gap cycle, so the cycle never stalls.
        if (beatInBar_ != 0) ++bar_;
        beatInBar_ = 0;
    }
    regridSubdivisions(frame);
}

void BeatGenerator::regridSubdivisions(std::int64_t frame) {
    // The next subdivision is the first point of the new grid, under the beat
    // that sounded last, that is still to come. Keeping the old ordinal would
    // re-fire a point that already sounded when the subdivision rises, and
    // play or count as late a point the old grid never scheduled when it
    // falls or the tempo moves (MET-05, MET-04).
    if (!haveLast_) {
        sub_ = params_.subdivision;
        return;
    }
    const double period = periodFrames();
    std::int32_t s = 1;
    while (s < params_.subdivision) {
        const std::int64_t onset = lastFrame_ + std::llround(static_cast<double>(s) / params_.subdivision * period);
        if (onset >= frame) break;
        ++s;
    }
    sub_ = s;
}

void BeatGenerator::onStreamStart(double sampleRate, std::int64_t frame) {
    prepare(sampleRate);
    if (!running_) return;
    // MET-09: as if the last beat had sounded on the first frame of the new
    // stream, so the next one comes one full period later and never at once.
    anchorIndex_ = nextIndex_ - 1;
    anchorFrame_ = frame;
    haveLast_ = true;
    lastFrame_ = frame;
    lastIndex_ = nextIndex_ - 1;
    sub_ = params_.subdivision;
}

std::int64_t BeatGenerator::nextSubOnset() const {
    if (!haveLast_ || sub_ >= params_.subdivision) return -1;
    const double fraction = static_cast<double>(sub_) / params_.subdivision;
    return lastFrame_ + std::llround(fraction * periodFrames());
}

void BeatGenerator::placeBeat(std::int64_t frame) {
    haveLast_ = true;
    lastFrame_ = frame;
    lastIndex_ = nextIndex_;
    sub_ = 1;
    ++nextIndex_;
    if (++beatInBar_ >= params_.beatsPerBar) {
        beatInBar_ = 0;
        ++bar_;
    }
}

std::int32_t BeatGenerator::render(std::int64_t blockStart, std::int32_t frames, ClickPlayer& clicks,
                                   BeatEvent* events, std::int32_t maxEvents) {
    if (!running_) return 0;
    const std::int64_t blockEnd = blockStart + frames;
    const std::int64_t lateFrames = std::llround(kLateSeconds * sampleRate_);
    std::int32_t written = 0;

    for (;;) {
        const std::int64_t beatOnset = onsetOf(nextIndex_);
        std::int64_t subOnset = nextSubOnset();
        if (subOnset >= 0 && subOnset >= beatOnset) {
            // A tempo edit moved the next beat under the remaining subdivisions.
            sub_ = params_.subdivision;
            subOnset = -1;
        }
        const bool isSub = subOnset >= 0 && subOnset < beatOnset;
        const std::int64_t onset = isSub ? subOnset : beatOnset;
        if (onset >= blockEnd) break;

        if (onset < blockStart - lateFrames) {
            // MET-04: never played late; counted so Diagnostics can show it.
            // A subdivision is counted apart, so the beat figure stays a
            // figure about beats.
            if (isSub) {
                ++lateSubdivisionsDropped_;
                ++sub_;
            } else {
                ++lateDropped_;
                placeBeat(onset);
            }
            continue;
        }
        const std::int32_t offset = onset < blockStart ? 0 : static_cast<std::int32_t>(onset - blockStart);
        const std::int64_t soundedAt = blockStart + offset;

        if (isSub) {
            // GAP-03: a muted beat keeps its subdivisions; a silent bar has none.
            if (lastBarSounds_) clicks.trigger(params_.click, params_.gain * kSubdivisionGain, offset);
            ++sub_;
            continue;
        }

        const Accent accent = params_.accents[beatInBar_];
        const float accentGain = kAccentGain[static_cast<std::int32_t>(accent)];
        lastBarSounds_ = params_.barSounds(bar_);
        const bool audible = lastBarSounds_ && accentGain > 0.0f;
        if (audible) clicks.trigger(params_.click, params_.gain * accentGain, offset);
        if (written < maxEvents) {
            events[written].index = nextIndex_;
            events[written].beatInBar = beatInBar_;
            events[written].frame = soundedAt;
            events[written].audible = audible;
            ++written;
        }
        placeBeat(soundedAt);
    }
    return written;
}

}  // namespace ticktock::render
