// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cmath>
#include <cstdint>

#include "render/Commands.h"

namespace ticktock::test {

// A MetroParams with every beat NORMAL; the tests override what they vary.
inline render::MetroParams params(int bpm, int beatsPerBar = 4, int subdivision = 1, float gain = 1.0f) {
    render::MetroParams p;
    p.bpm = bpm;
    p.beatsPerBar = beatsPerBar;
    p.beatUnit = 4;
    p.compoundPulse = false;
    for (render::Accent& a : p.accents) a = render::Accent::Normal;
    p.subdivision = subdivision;
    p.click = render::ClickSound::Wood;
    p.gain = gain;
    p.barsOn = 1;
    p.barsOff = 0;
    return p;
}

// MET-01 as written: onset(n) = A + round(n x sr x 60 / bpm).
inline std::int64_t onsetFormula(std::int64_t anchor, std::int64_t n, double sr, int bpm) {
    return anchor + std::llround(n * sr * 60.0 / bpm);
}

}  // namespace ticktock::test
