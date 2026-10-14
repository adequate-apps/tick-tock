// SPDX-License-Identifier: GPL-3.0-or-later
//
// MET-01..10, GAP-01..03 and the accent rule, rendered offline through the
// same RenderEngine the device callback drives.
#include "render/RenderEngine.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <set>
#include <vector>

#include "support/Params.h"
#include "support/RenderHarness.h"

using namespace ticktock;
using namespace ticktock::render;
using ticktock::test::onsetFormula;
using ticktock::test::onsets;
using ticktock::test::params;
using ticktock::test::Rendered;
using ticktock::test::renderFrames;
using ticktock::test::renderInto;

namespace {

Command start(const MetroParams& p) {
    Command c;
    c.kind = CommandKind::MetroStart;
    c.metro = p;
    return c;
}

Command update(const MetroParams& p) {
    Command c;
    c.kind = CommandKind::MetroUpdate;
    c.metro = p;
    return c;
}

Command stop() {
    Command c;
    c.kind = CommandKind::MetroStop;
    return c;
}

// Renders beats without keeping the audio, for the long runs.
std::vector<BeatEvent> renderBeats(RenderEngine& engine, std::int64_t frames, int block) {
    std::vector<float> buffer(static_cast<std::size_t>(block) * kChannels);
    std::vector<BeatEvent> beats;
    std::int64_t done = 0;
    while (done < frames) {
        const int n = static_cast<int>(frames - done < block ? frames - done : block);
        engine.render(buffer.data(), n);
        BeatEvent beat;
        while (engine.beats().tryPop(beat)) beats.push_back(beat);
        done += n;
    }
    return beats;
}

}  // namespace

// MET-01, MET-02: 10 000 beats at 300 bpm, every onset equal to the formula.
TEST(Metronome, tenThousandBeatsAt300BpmLandOnTheFormulaToTheFrame) {
    for (double sr : {48000.0, 44100.0}) {
        RenderEngine engine;
        engine.onStreamStart(sr);
        ASSERT_TRUE(engine.send(start(params(300))));
        const std::int64_t frames = llround(10000 * sr * 60.0 / 300) + 1;
        const auto beats = renderBeats(engine, frames, 240);
        ASSERT_EQ(10001u, beats.size()) << "at " << sr;
        for (std::size_t n = 0; n < beats.size(); ++n) {
            ASSERT_EQ(onsetFormula(0, static_cast<std::int64_t>(n), sr, 300), beats[n].frame) << "beat " << n << " at " << sr;
            ASSERT_EQ(static_cast<std::int64_t>(n), beats[n].index);
        }
        EXPECT_EQ(0, engine.lateDropped());
    }
}

// MET-02 with a period that rounds: 131 bpm at 44.1 kHz is 20198.47 frames.
TEST(Metronome, aFractionalPeriodNeverDrifts) {
    RenderEngine engine;
    engine.onStreamStart(44100.0);
    ASSERT_TRUE(engine.send(start(params(131))));
    const auto beats = renderBeats(engine, llround(10000 * 44100.0 * 60.0 / 131) + 1, 1024);
    ASSERT_EQ(10001u, beats.size());
    for (std::size_t n = 0; n < beats.size(); ++n) {
        ASSERT_EQ(onsetFormula(0, static_cast<std::int64_t>(n), 44100.0, 131), beats[n].frame) << "beat " << n;
    }
}

// MET-03: the first non-zero sample of every click is on its onset frame.
TEST(Metronome, theFirstNonZeroSampleLandsOnTheOnsetFrame) {
    for (double sr : {48000.0, 44100.0}) {
        RenderEngine engine;
        engine.onStreamStart(sr);
        ASSERT_TRUE(engine.send(start(params(137))));
        const std::int64_t frames = llround(20 * sr * 60.0 / 137) + 1;
        Rendered out = renderFrames(engine, static_cast<int>(frames), 192);
        const auto found = onsets(out.mono);
        ASSERT_EQ(21u, found.size()) << "at " << sr;
        ASSERT_EQ(21u, out.beats.size());
        for (std::size_t n = 0; n < found.size(); ++n) {
            EXPECT_EQ(onsetFormula(0, static_cast<std::int64_t>(n), sr, 137), found[n]) << "click " << n << " at " << sr;
            EXPECT_EQ(found[n], out.beats[n].frame);
        }
    }
}

// MET-04: an onset more than 2 ms in the past is dropped and counted.
TEST(Metronome, aBeatMoreThanTwoMillisecondsLateIsDroppedAndCounted) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(60))));
    Rendered out = renderFrames(engine, 40000, 256);  // beat 0 at 0; beat 1 due at 48000
    // Doubling the tempo puts beat 1 at 24000, far in the past: dropped.
    ASSERT_TRUE(engine.send(update(params(120))));
    renderInto(engine, 20000, 256, out);
    const auto found = onsets(out.mono);
    ASSERT_EQ(2u, found.size());
    EXPECT_EQ(0, found[0]);
    EXPECT_EQ(48000, found[1]);
    ASSERT_EQ(2u, out.beats.size());
    EXPECT_EQ(0, out.beats[0].index);
    EXPECT_EQ(2, out.beats[1].index);
    EXPECT_EQ(48000, out.beats[1].frame);
    EXPECT_EQ(1, engine.lateDropped());
}

TEST(Metronome, aBeatUnderTwoMillisecondsLatePlaysAtTheNextBlockAndIsNotCounted) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(60))));
    Rendered out = renderFrames(engine, 24040, 8);  // 40 frames (0.83 ms) past where beat 1 lands at 120
    ASSERT_TRUE(engine.send(update(params(120))));
    renderInto(engine, 1000, 8, out);
    ASSERT_EQ(2u, out.beats.size());
    EXPECT_EQ(1, out.beats[1].index);
    EXPECT_EQ(24040, out.beats[1].frame);
    EXPECT_EQ(0, engine.lateDropped());
    const auto found = onsets(out.mono);
    ASSERT_EQ(2u, found.size());
    EXPECT_EQ(24040, found[1]);
}

// MET-05: edits inside the latency window never double a beat and keep
// clicks at least min(old, new) period - 1 apart.
TEST(Metronome, liveEditsNeverDoubleABeatOrBunchClicks) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(120))));
    Rendered out = renderFrames(engine, 72000 + 100, 64);  // beat 3 just sounded
    const int tempos[] = {121, 119, 240, 60, 240, 300, 120};
    int minPeriod = 24000;
    for (int bpm : tempos) {
        ASSERT_TRUE(engine.send(update(params(bpm))));
        minPeriod = std::min(minPeriod, static_cast<int>(llround(48000.0 * 60 / bpm)));
        renderInto(engine, 64 * 3, 64, out);
    }
    renderInto(engine, 48000 * 4, 64, out);

    std::set<std::int64_t> indices;
    std::int64_t previous = -1;
    for (const BeatEvent& b : out.beats) {
        EXPECT_TRUE(indices.insert(b.index).second) << "beat " << b.index << " sounded twice";
        EXPECT_GT(b.index, previous);
        previous = b.index;
    }
    const auto found = onsets(out.mono);
    ASSERT_GE(found.size(), 8u);
    for (std::size_t i = 1; i < found.size(); ++i) {
        EXPECT_GE(found[i] - found[i - 1], minPeriod - 1) << "clicks " << i - 1 << " and " << i;
    }
    EXPECT_EQ(0, engine.lateDropped());
}

// MET-05 for subdivisions: raising the subdivision in the block right after
// a subdivision click must not fire the point that already sounded under the
// new grid. 120 bpm in halves: the half-beat click at 36000 sounds in the
// block [35968, 36032); the edit to quarters arrives at 36032, where the
// quarter points 30000 and 36000 are behind and 42000 is next.
TEST(Metronome, raisingTheSubdivisionNeverRefiresThePointThatJustSounded) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(120, 4, 2))));
    Rendered out = renderFrames(engine, 36032, 64);
    ASSERT_EQ((std::vector<std::int64_t>{0, 12000, 24000, 36000}), onsets(out.mono));
    ASSERT_TRUE(engine.send(update(params(120, 4, 4))));
    renderInto(engine, 48000, 64, out);
    const auto found = onsets(out.mono);
    const std::vector<std::int64_t> expected = {0, 12000, 24000, 36000, 42000, 48000, 54000, 60000, 66000, 72000, 78000, 84000};
    EXPECT_EQ(expected, found);
    EXPECT_EQ(0, engine.lateDropped());
    EXPECT_EQ(0, engine.generator().lateSubdivisionsDropped());
}

// MET-04 for subdivisions: lowering or raising the subdivision past a point
// the new grid places in the past skips that point, and the skip is no late
// beat. 120 bpm on beats; at 39040 the edit to halves puts 36000 behind.
TEST(Metronome, aSubdivisionPointAlreadyPassedAtTheEditIsSkippedAndNotCountedLate) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(120, 4, 1))));
    Rendered out = renderFrames(engine, 39040, 64);
    ASSERT_TRUE(engine.send(update(params(120, 4, 2))));
    renderInto(engine, 48000, 64, out);
    const auto found = onsets(out.mono);
    const std::vector<std::int64_t> expected = {0, 24000, 48000, 60000, 72000, 84000};
    EXPECT_EQ(expected, found);
    EXPECT_EQ(0, engine.lateDropped()) << "a point the grid never scheduled is no late beat";
    EXPECT_EQ(0, engine.generator().lateSubdivisionsDropped());
    ASSERT_EQ(4u, out.beats.size());
    EXPECT_EQ(3, out.beats[3].index);
}

// A tempo edit re-grids the subdivisions under the beat that sounded last;
// a point the new grid places in the past is skipped, never bunched onto the
// next block, and a subdivision that does arrive late is counted apart from
// the beats.
TEST(Metronome, aTempoEditRegridsTheSubdivisionsFromTheLastBeat) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(60, 4, 4))));  // period 48000, quarters at 12000
    Rendered out = renderFrames(engine, 48000 + 13000, 64);  // beat 1 at 48000, its first quarter at 60000 sounded
    ASSERT_TRUE(engine.send(update(params(120, 4, 4))));  // period 24000 from 48000: quarters at 54000 and 60000 are behind, 66000 is next
    renderInto(engine, 48000, 64, out);
    const auto found = onsets(out.mono);
    const std::vector<std::int64_t> expected = {0, 12000, 24000, 36000, 48000, 60000, 66000, 72000, 78000, 84000, 90000, 96000, 102000, 108000};
    EXPECT_EQ(expected, found);
    EXPECT_EQ(0, engine.lateDropped());
    EXPECT_EQ(0, engine.generator().lateSubdivisionsDropped());
}

// MET-05 with subdivisions: edits inside the latency window never double a
// beat, beat clicks stay min(old, new) period - 1 apart, and the beat row
// keeps its order.
TEST(Metronome, liveEditsWithSubdivisionsNeverDoubleABeat) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(120, 4, 4))));
    Rendered out = renderFrames(engine, 72000 + 100, 64);
    const int tempos[] = {121, 119, 240, 60, 240, 300, 120};
    const int subdivisions[] = {3, 4, 2, 4, 1, 4, 3};
    int minPeriod = 24000;
    for (int i = 0; i < 7; ++i) {
        ASSERT_TRUE(engine.send(update(params(tempos[i], 4, subdivisions[i]))));
        minPeriod = std::min(minPeriod, static_cast<int>(llround(48000.0 * 60 / tempos[i])));
        renderInto(engine, 64 * 3, 64, out);
    }
    renderInto(engine, 48000 * 4, 64, out);

    std::set<std::int64_t> indices;
    std::int64_t previous = -1;
    std::int64_t previousFrame = -1;
    for (const BeatEvent& b : out.beats) {
        EXPECT_TRUE(indices.insert(b.index).second) << "beat " << b.index << " sounded twice";
        EXPECT_GT(b.index, previous);
        if (previousFrame >= 0) {
            EXPECT_GE(b.frame - previousFrame, minPeriod - 1) << "beats " << previous << " and " << b.index;
        }
        previous = b.index;
        previousFrame = b.frame;
    }
    EXPECT_EQ(0, engine.lateDropped());
    EXPECT_EQ(0, engine.generator().lateSubdivisionsDropped());
}

// MET-06: beat m+1 sounds at onset(m) + round(sr x 60 / bpm_new).
TEST(Metronome, aTempoChangeReanchorsOnTheBeatThatSounded) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(100))));  // period 28800
    Rendered out = renderFrames(engine, 28800 + 5000, 256);  // beats 0 and 1 sounded
    ASSERT_TRUE(engine.send(update(params(90))));  // period 32000
    renderInto(engine, 100000, 256, out);
    ASSERT_GE(out.beats.size(), 4u);
    EXPECT_EQ(28800, out.beats[1].frame);
    EXPECT_EQ(28800 + 32000, out.beats[2].frame);
    EXPECT_EQ(28800 + 2 * 32000, out.beats[3].frame);
    const auto found = onsets(out.mono);
    ASSERT_GE(found.size(), 3u);
    EXPECT_EQ(28800 + 32000, found[2]);
}

// MET-07: a metre change resets the bar.
TEST(Metronome, aMetreChangeResetsTheBar) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(240, 4))));  // period 12000
    Rendered out = renderFrames(engine, 12000 * 5 + 100, 256);  // beats 0..5; beat 5 is beat 1 of bar 1
    EXPECT_EQ(1, out.beats[5].beatInBar);
    ASSERT_TRUE(engine.send(update(params(240, 3))));
    renderInto(engine, 12000 * 6, 256, out);
    ASSERT_GE(out.beats.size(), 12u);
    const int expected[] = {0, 1, 2, 3, 0, 1, 0, 1, 2, 0, 1, 2};
    for (int i = 0; i < 12; ++i) EXPECT_EQ(expected[i], out.beats[static_cast<std::size_t>(i)].beatInBar) << "beat " << i;
    // Timing is untouched by a metre change.
    for (int i = 0; i < 12; ++i) EXPECT_EQ(12000LL * i, out.beats[static_cast<std::size_t>(i)].frame) << "beat " << i;
}

// MET-08: 64 beats through a tempo change, a metre change, a restart and an
// edit inside the latency window, each onset matching the expected frame.
TEST(Metronome, sixtyFourBeatScenarioMatchesTheExpectedFrames) {
    const double sr = 44100.0;
    const int block = 256;
    RenderEngine engine;
    engine.onStreamStart(sr);
    std::vector<std::int64_t> expected;
    Rendered out;

    // 1. 120 bpm 4/4 from frame 0: beats 0..15.
    ASSERT_TRUE(engine.send(start(params(120, 4))));
    for (int n = 0; n < 16; ++n) expected.push_back(onsetFormula(0, n, sr, 120));
    renderInto(engine, static_cast<int>(expected.back() + 300), block, out);

    // 2. Tempo to 97: the grid grows from beat 15's frame (MET-06).
    ASSERT_TRUE(engine.send(update(params(97, 4))));
    const std::int64_t anchor15 = expected.back();
    for (int n = 1; n <= 16; ++n) expected.push_back(onsetFormula(anchor15, n, sr, 97));
    renderInto(engine, static_cast<int>(expected.back() - out.mono.size() + 300), block, out);

    // 3. Metre to 3/4 at the same tempo: timing unchanged, bar reset (MET-07).
    ASSERT_TRUE(engine.send(update(params(97, 3))));
    for (int n = 17; n <= 24; ++n) expected.push_back(onsetFormula(anchor15, n, sr, 97));
    renderInto(engine, static_cast<int>(expected.back() - out.mono.size() + 300), block, out);

    // 4. Restart: stop, wait, start again; the first beat lands on the first
    // frame of the block the command arrives in.
    ASSERT_TRUE(engine.send(stop()));
    renderInto(engine, 5000, block, out);
    const std::int64_t restartAt = static_cast<std::int64_t>(out.mono.size());
    ASSERT_TRUE(engine.send(start(params(150, 4))));
    for (int n = 0; n < 12; ++n) expected.push_back(onsetFormula(restartAt, n, sr, 150));
    renderInto(engine, static_cast<int>(expected.back() - out.mono.size() + 50), block, out);

    // 5. An edit inside the latency window: the tempo changes within 50
    // frames of beat 11 sounding; beat 12 comes one new period after it.
    ASSERT_TRUE(engine.send(update(params(200, 4))));
    const std::int64_t anchor11 = expected.back();
    for (int n = 1; n <= 12; ++n) expected.push_back(onsetFormula(anchor11, n, sr, 200));
    renderInto(engine, static_cast<int>(expected.back() - out.mono.size() + 1), block, out);

    ASSERT_EQ(64u, expected.size());
    ASSERT_EQ(64u, out.beats.size());
    for (std::size_t i = 0; i < 64; ++i) EXPECT_EQ(expected[i], out.beats[i].frame) << "beat " << i;
    const auto found = onsets(out.mono);
    ASSERT_EQ(64u, found.size());
    for (std::size_t i = 0; i < 64; ++i) EXPECT_EQ(expected[i], found[i]) << "click " << i;
    // Bar positions: 16 at 4/4, 16 at 4/4, 8 at 3/4 from a downbeat, 12 then 12 at 4/4 from the restart.
    EXPECT_EQ(0, out.beats[32].beatInBar);
    EXPECT_EQ(2, out.beats[34].beatInBar);
    EXPECT_EQ(0, out.beats[40].beatInBar);
    EXPECT_EQ(0, engine.lateDropped());
}

// MET-09, OUT-13: after an output switch the next click comes one beat after
// the first frame of the new stream, never at once.
TEST(Metronome, afterAnOutputSwitchTheNextClickComesOneBeatLater) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(120, 4))));
    Rendered before = renderFrames(engine, 30000, 256);  // beats 0 and 1 (frame 24000)
    ASSERT_EQ(2u, before.beats.size());

    engine.onStreamStart(44100.0);
    Rendered after = renderFrames(engine, 44100 + 1, 256);
    const auto found = onsets(after.mono);
    ASSERT_GE(found.size(), 1u);
    EXPECT_EQ(22050, found[0]) << "an immediate click fails";
    ASSERT_GE(after.beats.size(), 2u);
    EXPECT_EQ(2, after.beats[0].index) << "the beat count continues";
    EXPECT_EQ(22050, after.beats[0].frame);
    EXPECT_EQ(2, after.beats[0].beatInBar) << "the bar position continues";
    EXPECT_EQ(44100, after.beats[1].frame);
}

// MET-10: 30 to 300 bpm; anything outside is clamped, never crashes.
TEST(Metronome, acceptsThirtyToThreeHundredAndClampsOutside) {
    struct Case { int bpm; std::int64_t period; };
    const Case cases[] = {{30, 96000}, {300, 9600}, {10, 96000}, {400, 9600}};
    for (const Case& c : cases) {
        RenderEngine engine;
        engine.onStreamStart(48000.0);
        ASSERT_TRUE(engine.send(start(params(c.bpm))));
        const auto beats = renderBeats(engine, c.period * 3 + 1, 512);
        ASSERT_EQ(4u, beats.size()) << "bpm " << c.bpm;
        EXPECT_EQ(c.period, beats[1].frame) << "bpm " << c.bpm;
        EXPECT_EQ(2 * c.period, beats[2].frame);
    }
}

// The accent row: ACCENT 1.0, NORMAL 0.7, SOFT 0.4, MUTE silent (MET-02 note).
// The muted beat sits at frame 0, inside the stream's 5 ms fade-in, so the
// three levels are read from beats outside it.
TEST(Metronome, accentLevelsAreGainMultipliersAndMuteIsSilent) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    MetroParams p = params(240, 4, 1, 1.0f);
    p.accents[0] = Accent::Mute;
    p.accents[1] = Accent::Accent;
    p.accents[2] = Accent::Normal;
    p.accents[3] = Accent::Soft;
    ASSERT_TRUE(engine.send(start(p)));
    Rendered out = renderFrames(engine, 12000 * 4, 256);
    EXPECT_EQ(0.0f, out.mono[0]);
    EXPECT_FLOAT_EQ(kWoodData[0] * 1.0f, out.mono[12000]);
    EXPECT_FLOAT_EQ(kWoodData[0] * 0.7f, out.mono[24000]);
    EXPECT_FLOAT_EQ(kWoodData[0] * 0.4f, out.mono[36000]);
    ASSERT_EQ(4u, out.beats.size());
    EXPECT_FALSE(out.beats[0].audible);
    EXPECT_TRUE(out.beats[1].audible);
    EXPECT_EQ((std::vector<std::int64_t>{12000, 24000, 36000}), onsets(out.mono));
}

// GAP-01: 3 on / 1 off: bars 1-3 sound, bar 4 is silent, bar 5 sounds, and
// the beat events keep coming through the silent bar.
TEST(Metronome, gapBarsSilenceWholeBarsWhileBeatsKeepComing) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    MetroParams p = params(240, 4, 1, 1.0f);  // period 12000, bar 48000
    p.barsOn = 3;
    p.barsOff = 1;
    ASSERT_TRUE(engine.send(start(p)));
    Rendered out = renderFrames(engine, 48000 * 5, 256);
    ASSERT_EQ(20u, out.beats.size());
    for (int i = 0; i < 20; ++i) {
        EXPECT_EQ(i, out.beats[static_cast<std::size_t>(i)].index);
        EXPECT_EQ(12000LL * i, out.beats[static_cast<std::size_t>(i)].frame);
        EXPECT_EQ(i < 12 || i >= 16, out.beats[static_cast<std::size_t>(i)].audible) << "beat " << i;
    }
    const auto found = onsets(out.mono);
    ASSERT_EQ(16u, found.size());
    for (std::int64_t f : found) EXPECT_TRUE(f < 48000 * 3 || f >= 48000 * 4) << "a click inside the silent bar at " << f;
    EXPECT_EQ(48000 * 4, found[12]) << "bar 5 sounds again";
}

// GAP-02: a tempo change keeps the cycle's bar position.
TEST(Metronome, gapBarsKeepTheirBarPositionAcrossATempoChange) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    MetroParams p = params(120, 4, 1, 1.0f);  // period 24000
    p.barsOn = 2;
    p.barsOff = 1;
    ASSERT_TRUE(engine.send(start(p)));
    Rendered out = renderFrames(engine, 24000 * 5 + 100, 256);  // beats 0..5 sounded
    MetroParams faster = p;
    faster.bpm = 90;  // period 32000
    ASSERT_TRUE(engine.send(update(faster)));
    renderInto(engine, 32000 * 8, 256, out);
    ASSERT_GE(out.beats.size(), 13u);
    for (int i = 0; i < 13; ++i) {
        const bool inBarThree = i >= 8 && i < 12;
        EXPECT_EQ(!inBarThree, out.beats[static_cast<std::size_t>(i)].audible) << "beat " << i;
    }
    // Beat 6 onward follows MET-06 from beat 5's frame.
    for (int i = 6; i < 13; ++i) EXPECT_EQ(onsetFormula(24000 * 5, i - 5, 48000.0, 90), out.beats[static_cast<std::size_t>(i)].frame) << "beat " << i;
}

// GAP-03: a muted beat keeps its subdivisions at the sub level.
TEST(Metronome, aMutedBeatKeepsItsSubdivisionsAudible) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    MetroParams p = params(120, 4, 2, 1.0f);  // period 24000, subs at +12000
    p.accents[1] = Accent::Mute;
    ASSERT_TRUE(engine.send(start(p)));
    Rendered out = renderFrames(engine, 24000 * 3, 256);
    const auto found = onsets(out.mono);
    const std::vector<std::int64_t> expected = {0, 12000, 36000, 48000, 60000};
    EXPECT_EQ(expected, found);
    EXPECT_FLOAT_EQ(kWoodData[0] * kSubdivisionGain, out.mono[12000]);
    EXPECT_FLOAT_EQ(kWoodData[0] * kSubdivisionGain, out.mono[36000]);
    EXPECT_FLOAT_EQ(kWoodData[0] * 0.7f, out.mono[48000]);
    ASSERT_EQ(3u, out.beats.size());
    EXPECT_FALSE(out.beats[1].audible);
    EXPECT_EQ(24000, out.beats[1].frame) << "the muted beat still reports its onset for the beat row";
}

// A silent gap bar has no subdivisions either.
TEST(Metronome, aSilentBarHasNoSubdivisionClicks) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    MetroParams p = params(240, 2, 2, 1.0f);  // period 12000, bar 24000, subs at +6000
    p.barsOn = 1;
    p.barsOff = 1;
    ASSERT_TRUE(engine.send(start(p)));
    Rendered out = renderFrames(engine, 24000 * 2, 256);
    const auto found = onsets(out.mono);
    const std::vector<std::int64_t> expected = {0, 6000, 12000, 18000};
    EXPECT_EQ(expected, found);
}

// The compound pulse: 6/8 at 60 pulses on the dotted quarter, so each of the
// six beats is a third of the pulse.
TEST(Metronome, compoundPulseDividesThePulseInThree) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    MetroParams p = params(60, 6);
    p.beatUnit = 8;
    p.compoundPulse = true;
    ASSERT_TRUE(engine.send(start(p)));
    const auto beats = renderBeats(engine, 16000 * 6 + 1, 256);
    ASSERT_EQ(7u, beats.size());
    for (int i = 0; i < 7; ++i) EXPECT_EQ(16000LL * i, beats[static_cast<std::size_t>(i)].frame);
    EXPECT_EQ(0, beats[6].beatInBar);
}

// MetroStop ends the beats and the clicks at once.
TEST(Metronome, stopLeavesNoFurtherBeats) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(start(params(120))));
    Rendered out = renderFrames(engine, 24000 + 100, 256);
    ASSERT_TRUE(engine.send(stop()));
    renderInto(engine, 48000 * 2, 256, out);
    EXPECT_EQ(2u, out.beats.size());
    EXPECT_EQ(2u, onsets(out.mono).size());
}
