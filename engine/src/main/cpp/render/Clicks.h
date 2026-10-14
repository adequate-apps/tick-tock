// SPDX-License-Identifier: GPL-3.0-or-later
//
// The three click sounds and the small pool of players that voice them. The
// samples are baked arrays (ClickData.cpp), so there is no asset load, and a
// player steps through them at the stream rate so a click keeps its length
// on a 44.1 kHz device.
#pragma once

#include <cstdint>

#include "Commands.h"

namespace ticktock::render {

constexpr std::int32_t kClickFrames = 1440;
constexpr double kClickRate = 48000.0;

extern const float kWoodData[kClickFrames];
extern const float kClickData[kClickFrames];
extern const float kBeepData[kClickFrames];

inline const float* clickData(ClickSound sound) {
    switch (sound) {
        case ClickSound::Click: return kClickData;
        case ClickSound::Beep: return kBeepData;
        case ClickSound::Wood: break;
    }
    return kWoodData;
}

class ClickPlayer {
public:
    // Clicks are 30 ms and the closest legal spacing is 50 ms (300 bpm in
    // sixteenths), so two players never overlap in steady state; the pool
    // only has to absorb a tempo edit that lands one click near another.
    static constexpr std::int32_t kPlayers = 4;

    void prepare(double sampleRate) {
        step_ = sampleRate > 0.0 ? kClickRate / sampleRate : 1.0;
        for (Player& p : players_) p.active = false;
    }

    // Schedules a click whose first sample lands at `offset` frames into the
    // block about to be rendered. Call before render() for that block.
    void trigger(ClickSound sound, float gain, std::int32_t offset) {
        Player* chosen = nullptr;
        for (Player& p : players_) {
            if (!p.active) { chosen = &p; break; }
        }
        if (chosen == nullptr) {
            // Every player busy: steal the one furthest into its sound.
            chosen = &players_[0];
            for (Player& p : players_) {
                if (p.position > chosen->position) chosen = &p;
            }
        }
        chosen->active = true;
        chosen->data = clickData(sound);
        chosen->gain = gain;
        chosen->position = 0.0;
        chosen->startOffset = offset;
    }

    // Adds the active clicks into `mono`.
    void render(float* mono, std::int32_t frames) {
        for (Player& p : players_) {
            if (!p.active) continue;
            std::int32_t i = p.startOffset > 0 ? p.startOffset : 0;
            p.startOffset = 0;
            for (; i < frames; ++i) {
                const std::int32_t index = static_cast<std::int32_t>(p.position);
                if (index >= kClickFrames) { p.active = false; break; }
                mono[i] += p.data[index] * p.gain;
                p.position += step_;
            }
        }
    }

    std::int32_t activeCount() const {
        std::int32_t n = 0;
        for (const Player& p : players_) n += p.active ? 1 : 0;
        return n;
    }

private:
    struct Player {
        bool active = false;
        const float* data = kWoodData;
        float gain = 0.0f;
        double position = 0.0;
        std::int32_t startOffset = 0;
    };
    Player players_[kPlayers];
    double step_ = 1.0;
};

}  // namespace ticktock::render
