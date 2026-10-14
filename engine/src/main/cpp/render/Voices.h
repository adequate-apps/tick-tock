// SPDX-License-Identifier: GPL-3.0-or-later
//
// The synthesised voices: sine, warm (fundamental plus four harmonics) and
// root+fifth. A slot keeps its phase across a retune and a gain change, so a
// held note never re-strikes (TON-03, TON-04); the phase accumulators are
// doubles so the sine lands inside 0.1 cent (TON-02).
#pragma once

#include <cmath>
#include <cstdint>

#include "Commands.h"

namespace ticktock::render {

constexpr double kTwoPi = 6.283185307179586476925286766559;
// Gain moves over this long so a volume change or a note off never pops.
constexpr double kGainRampSeconds = 0.005;

class VoiceBank {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate > 0.0 ? sampleRate : 48000.0;
        rampStep_ = static_cast<float>(1.0 / (kGainRampSeconds * sampleRate_));
        for (Slot& s : slots_) retune(s);
    }

    // Retunes every sounding slot at the next block, in place (TON-03).
    void setA4(double hz) {
        a4_ = hz > 0.0 ? hz : 440.0;
        for (Slot& s : slots_) retune(s);
    }

    // Starts a voice, or retunes one already sounding in that slot without
    // touching its phase, so a pad change on the drone is seamless.
    void noteOn(std::int32_t slot, std::int32_t midi, Voice voice, float gain) {
        if (!valid(slot)) return;
        Slot& s = slots_[slot];
        if (!s.on) {
            s.phase = 0.0;
            s.phaseFifth = 0.0;
            s.gain = 0.0f;
        }
        s.on = true;
        s.releasing = false;
        s.midi = midi;
        s.voice = voice;
        s.target = clamp01(gain);
        retune(s);
    }

    // Ramps to silence, then frees the slot (TON-04 keeps the count steady
    // through gain changes; only a note off changes it).
    void noteOff(std::int32_t slot) {
        if (!valid(slot)) return;
        Slot& s = slots_[slot];
        if (!s.on) return;
        s.releasing = true;
        s.target = 0.0f;
    }

    void setGain(std::int32_t slot, float gain) {
        if (!valid(slot)) return;
        Slot& s = slots_[slot];
        if (!s.on || s.releasing) return;
        s.target = clamp01(gain);
    }

    // Adds every sounding slot into `mono`.
    void render(float* mono, std::int32_t frames) {
        for (Slot& s : slots_) {
            if (!s.on) continue;
            for (std::int32_t i = 0; i < frames; ++i) {
                mono[i] += sample(s) * s.gain;
                advance(s);
                rampGain(s);
            }
            if (s.releasing && s.gain <= 0.0f) {
                s.on = false;
                s.releasing = false;
            }
        }
    }

    std::int32_t activeCount() const {
        std::int32_t n = 0;
        for (const Slot& s : slots_) n += s.on ? 1 : 0;
        return n;
    }

    double frequencyOf(std::int32_t slot) const {
        return valid(slot) ? slots_[slot].hz : 0.0;
    }

    void silenceAll() {
        for (Slot& s : slots_) {
            s.on = false;
            s.releasing = false;
            s.gain = 0.0f;
            s.target = 0.0f;
        }
    }

private:
    struct Slot {
        bool on = false;
        bool releasing = false;
        Voice voice = Voice::Sine;
        std::int32_t midi = 69;
        double hz = 440.0;
        double phase = 0.0;
        double phaseFifth = 0.0;
        double increment = 0.0;
        double incrementFifth = 0.0;
        float gain = 0.0f;
        float target = 0.0f;
    };

    static bool valid(std::int32_t slot) { return slot >= 0 && slot < kVoiceSlots; }
    static float clamp01(float g) { return g < 0.0f ? 0.0f : (g > 1.0f ? 1.0f : g); }

    void retune(Slot& s) {
        s.hz = a4_ * std::pow(2.0, (s.midi - 69) / 12.0);
        s.increment = kTwoPi * s.hz / sampleRate_;
        // A just fifth (3:2) is beatless against the root, which is what a
        // drone for intonation practice is for.
        s.incrementFifth = s.increment * 1.5;
    }

    static float sample(const Slot& s) {
        switch (s.voice) {
            case Voice::Sine:
                return static_cast<float>(std::sin(s.phase));
            case Voice::Warm:
                // 1, 0.5, 0.25, 0.12, 0.06 over harmonics 1..5, scaled to unit peak sum.
                return static_cast<float>((std::sin(s.phase) + 0.5 * std::sin(2.0 * s.phase)
                                           + 0.25 * std::sin(3.0 * s.phase) + 0.12 * std::sin(4.0 * s.phase)
                                           + 0.06 * std::sin(5.0 * s.phase)) / 1.93);
            case Voice::RootFifth:
                return static_cast<float>((std::sin(s.phase) + 0.5 * std::sin(s.phaseFifth)) / 1.5);
        }
        return 0.0f;
    }

    static void advance(Slot& s) {
        s.phase += s.increment;
        if (s.phase >= kTwoPi) s.phase -= kTwoPi;
        s.phaseFifth += s.incrementFifth;
        if (s.phaseFifth >= kTwoPi) s.phaseFifth -= kTwoPi;
    }

    void rampGain(Slot& s) const {
        if (s.gain < s.target) {
            s.gain += rampStep_;
            if (s.gain > s.target) s.gain = s.target;
        } else if (s.gain > s.target) {
            s.gain -= rampStep_;
            if (s.gain < s.target) s.gain = s.target;
        }
    }

    Slot slots_[kVoiceSlots];
    double sampleRate_ = 48000.0;
    double a4_ = 440.0;
    float rampStep_ = 0.004f;
};

}  // namespace ticktock::render
