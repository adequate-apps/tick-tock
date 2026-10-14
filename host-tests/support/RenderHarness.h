// SPDX-License-Identifier: GPL-3.0-or-later
//
// Offline rendering helpers: run the engine for N frames in blocks, keep the
// mono signal and every beat event, and find click onsets in the signal.
#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

#include "render/RenderEngine.h"

namespace ticktock::test {

struct Rendered {
    std::vector<float> mono;              // left channel; the mix is identical on both
    std::vector<render::BeatEvent> beats;
};

// Renders `frames` frames in `block`-sized callbacks, appending to `out`.
inline void renderInto(render::RenderEngine& engine, std::int32_t frames, std::int32_t block, Rendered& out) {
    std::vector<float> buffer(static_cast<std::size_t>(block) * render::kChannels);
    std::int32_t done = 0;
    while (done < frames) {
        const std::int32_t n = frames - done < block ? frames - done : block;
        engine.render(buffer.data(), n);
        for (std::int32_t i = 0; i < n; ++i) out.mono.push_back(buffer[static_cast<std::size_t>(i) * render::kChannels]);
        render::BeatEvent beat;
        while (engine.beats().tryPop(beat)) out.beats.push_back(beat);
        done += n;
    }
}

inline Rendered renderFrames(render::RenderEngine& engine, std::int32_t frames, std::int32_t block) {
    Rendered out;
    renderInto(engine, frames, block, out);
    return out;
}

// Frames at which the signal becomes non-zero after at least `gap` zero
// frames: the first non-zero sample of each click (MET-03).
inline std::vector<std::int64_t> onsets(const std::vector<float>& mono, std::int64_t gap = 1) {
    std::vector<std::int64_t> found;
    std::int64_t zeros = gap;
    for (std::size_t i = 0; i < mono.size(); ++i) {
        if (mono[i] != 0.0f) {
            if (zeros >= gap) found.push_back(static_cast<std::int64_t>(i));
            zeros = 0;
        } else {
            ++zeros;
        }
    }
    return found;
}

// Peak frequency near `guessHz` from a Hann-windowed DFT evaluated on a
// continuous frequency axis and refined by ternary search, so the estimate
// is far finer than one bin. Good to well under 0.05 cent on a clean tone.
inline double peakHz(const std::vector<float>& x, double sampleRate, double guessHz, double searchCents = 50.0) {
    const std::size_t n = x.size();
    std::vector<double> windowed(n);
    for (std::size_t i = 0; i < n; ++i) {
        const double w = 0.5 - 0.5 * std::cos(2.0 * M_PI * static_cast<double>(i) / static_cast<double>(n - 1));
        windowed[i] = x[i] * w;
    }
    auto magnitude = [&](double hz) {
        double re = 0.0, im = 0.0;
        const double step = 2.0 * M_PI * hz / sampleRate;
        for (std::size_t i = 0; i < n; ++i) {
            re += windowed[i] * std::cos(step * static_cast<double>(i));
            im -= windowed[i] * std::sin(step * static_cast<double>(i));
        }
        return re * re + im * im;
    };
    double lo = guessHz * std::pow(2.0, -searchCents / 1200.0);
    double hi = guessHz * std::pow(2.0, searchCents / 1200.0);
    // Coarse scan first so the ternary search starts on the right lobe.
    double best = guessHz, bestMag = -1.0;
    for (int k = 0; k <= 40; ++k) {
        const double hz = lo + (hi - lo) * k / 40.0;
        const double m = magnitude(hz);
        if (m > bestMag) { bestMag = m; best = hz; }
    }
    const double stepHz = (hi - lo) / 40.0;
    lo = best - stepHz;
    hi = best + stepHz;
    for (int it = 0; it < 60; ++it) {
        const double a = lo + (hi - lo) / 3.0;
        const double b = hi - (hi - lo) / 3.0;
        if (magnitude(a) < magnitude(b)) lo = a; else hi = b;
    }
    return (lo + hi) / 2.0;
}

inline double centsBetween(double hz, double targetHz) {
    return 1200.0 * std::log2(hz / targetHz);
}

inline double midiHz(int midi, double a4 = 440.0) {
    return a4 * std::pow(2.0, (midi - 69) / 12.0);
}

}  // namespace ticktock::test
