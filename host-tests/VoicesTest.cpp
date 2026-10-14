// SPDX-License-Identifier: GPL-3.0-or-later
//
// TON-02..04 and the mix rules (duck floor, mute gate), rendered offline.
#include "render/RenderEngine.h"

#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "support/RenderHarness.h"

using namespace ticktock;
using namespace ticktock::render;
using ticktock::test::centsBetween;
using ticktock::test::midiHz;
using ticktock::test::peakHz;
using ticktock::test::Rendered;
using ticktock::test::renderFrames;
using ticktock::test::renderInto;

namespace {

Command voiceOn(int slot, int midi, Voice voice, float gain) {
    Command c;
    c.kind = CommandKind::VoiceOn;
    c.slot = slot;
    c.midi = midi;
    c.voice = voice;
    c.gain = gain;
    return c;
}

Command voiceGain(int slot, float gain) {
    Command c;
    c.kind = CommandKind::VoiceGain;
    c.slot = slot;
    c.gain = gain;
    return c;
}

Command voiceOff(int slot) {
    Command c;
    c.kind = CommandKind::VoiceOff;
    c.slot = slot;
    return c;
}

Command setA4(double hz) {
    Command c;
    c.kind = CommandKind::SetA4;
    c.a4 = hz;
    return c;
}

Command duck(float gain) {
    Command c;
    c.kind = CommandKind::Duck;
    c.gain = gain;
    return c;
}

Command mute(MuteReason reason, bool active) {
    Command c;
    c.kind = CommandKind::Mute;
    c.muteReason = reason;
    c.active = active;
    return c;
}

std::vector<float> slice(const std::vector<float>& v, std::size_t from, std::size_t count) {
    return std::vector<float>(v.begin() + static_cast<std::ptrdiff_t>(from),
                              v.begin() + static_cast<std::ptrdiff_t>(from + count));
}

float peakAbs(const std::vector<float>& v, std::size_t from, std::size_t count) {
    float peak = 0.0f;
    for (std::size_t i = from; i < from + count && i < v.size(); ++i) peak = std::max(peak, std::fabs(v[i]));
    return peak;
}

float maxStep(const std::vector<float>& v, std::size_t from, std::size_t count) {
    float worst = 0.0f;
    for (std::size_t i = from + 1; i < from + count && i < v.size(); ++i) worst = std::max(worst, std::fabs(v[i] - v[i - 1]));
    return worst;
}

}  // namespace

// TON-02: at A4 = 442 every voice is within 0.5 c of its target; sine within 0.1 c.
TEST(Voices, everyVoiceIsInTuneAt442) {
    const int notes[] = {45, 57, 62, 69, 76, 81, 93};
    for (double sr : {48000.0, 44100.0}) {
        for (Voice voice : {Voice::Sine, Voice::Warm, Voice::RootFifth}) {
            for (int midi : notes) {
                RenderEngine engine;
                engine.onStreamStart(sr);
                ASSERT_TRUE(engine.send(setA4(442.0)));
                ASSERT_TRUE(engine.send(voiceOn(1, midi, voice, 0.8f)));
                const int settle = static_cast<int>(sr * 0.02);
                const int window = static_cast<int>(sr * 0.5);
                Rendered out = renderFrames(engine, settle + window, 480);
                const double target = midiHz(midi, 442.0);
                const double hz = peakHz(slice(out.mono, static_cast<std::size_t>(settle), static_cast<std::size_t>(window)), sr, target);
                const double cents = std::fabs(centsBetween(hz, target));
                const double bound = voice == Voice::Sine ? 0.1 : 0.5;
                EXPECT_LT(cents, bound) << "voice " << static_cast<int>(voice) << " midi " << midi << " at " << sr << ": " << hz << " Hz";
            }
        }
    }
}

// TON-03: a held note retunes within one block, with no gap and no re-strike.
TEST(Voices, aHeldNoteRetunesInPlaceWhenA4Changes) {
    const double sr = 48000.0;
    RenderEngine engine;
    engine.onStreamStart(sr);
    ASSERT_TRUE(engine.send(voiceOn(0, 69, Voice::Sine, 0.8f)));
    Rendered out = renderFrames(engine, 24000, 240);
    EXPECT_LT(std::fabs(centsBetween(peakHz(slice(out.mono, 1000, 20000), sr, 440.0), 440.0)), 0.1);

    const std::size_t change = out.mono.size();
    ASSERT_TRUE(engine.send(setA4(442.0)));
    renderInto(engine, 24000, 240, out);
    EXPECT_LT(std::fabs(centsBetween(peakHz(slice(out.mono, change + 240, 20000), sr, 442.0), 442.0)), 0.1);
    EXPECT_EQ(1, engine.voices().activeCount());

    // No gap: every 5 ms window around the change keeps its level.
    const float steady = peakAbs(out.mono, 1000, 2000);
    for (std::size_t w = change - 2400; w < change + 2400; w += 240) {
        EXPECT_GT(peakAbs(out.mono, w, 240), steady * 0.9f) << "level dropped at " << w;
    }
    // No re-strike: the waveform stays continuous across the change (a
    // 442 Hz sine at 0.8 moves at most 0.047 per sample).
    EXPECT_LT(maxStep(out.mono, change - 10, 20), 0.06f);
}

// TON-04: volume is a gain ramp; the voice count stays constant and nothing
// re-strikes.
TEST(Voices, volumeIsAGainChangeAndNotARestrike) {
    const double sr = 48000.0;
    RenderEngine engine;
    engine.onStreamStart(sr);
    ASSERT_TRUE(engine.send(voiceOn(2, 60, Voice::Warm, 0.8f)));
    Rendered out = renderFrames(engine, 9600, 240);
    EXPECT_EQ(1, engine.voices().activeCount());
    const float before = peakAbs(out.mono, 4800, 4800);

    const std::size_t change = out.mono.size();
    ASSERT_TRUE(engine.send(voiceGain(2, 0.25f)));
    renderInto(engine, 9600, 240, out);
    EXPECT_EQ(1, engine.voices().activeCount());
    const float after = peakAbs(out.mono, change + 480, 9000);
    EXPECT_NEAR(before * 0.25f / 0.8f, after, before * 0.02f);
    // The 5 ms ramp is visible as a smooth descent: no sample jumps more
    // than a warm tone at 0.8 can move on its own plus the ramp step.
    const float maxSlope = static_cast<float>(2.0 * M_PI * midiHz(60) * 5.0 / sr) * 0.8f + 0.01f;
    EXPECT_LT(maxStep(out.mono, change - 5, 300), maxSlope);

    ASSERT_TRUE(engine.send(voiceOff(2)));
    renderInto(engine, 480, 240, out);
    EXPECT_EQ(0, engine.voices().activeCount());
    EXPECT_EQ(0.0f, out.mono.back());
}

// OUT-13: a held voice keeps its pitch when the stream reopens at another rate.
TEST(Voices, aHeldVoiceKeepsItsPitchAcrossARateChange) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(voiceOn(1, 69, Voice::RootFifth, 0.8f)));
    renderFrames(engine, 4800, 240);
    engine.onStreamStart(44100.0);
    Rendered out = renderFrames(engine, 44100 / 2 + 2000, 240);
    const double hz = peakHz(slice(out.mono, 1000, 22050), 44100.0, 440.0);
    EXPECT_LT(std::fabs(centsBetween(hz, 440.0)), 0.5);
    EXPECT_EQ(1, engine.voices().activeCount());
}

// POL-26: the duck is a ramped gain with 0.3 as its floor.
TEST(Mixer, duckIsARampedGainWithAFloor) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(voiceOn(0, 69, Voice::Sine, 1.0f)));
    Rendered out = renderFrames(engine, 4800, 240);
    const float full = peakAbs(out.mono, 2400, 2400);
    EXPECT_NEAR(1.0f, full, 0.01f);

    ASSERT_TRUE(engine.send(duck(0.5f)));
    renderInto(engine, 4800, 240, out);
    EXPECT_NEAR(0.5f, peakAbs(out.mono, 4800 + 480, 4000), 0.01f);

    ASSERT_TRUE(engine.send(duck(0.1f)));
    renderInto(engine, 4800, 240, out);
    EXPECT_NEAR(0.3f, peakAbs(out.mono, 9600 + 480, 4000), 0.01f) << "0.3 is the floor";

    ASSERT_TRUE(engine.send(duck(1.0f)));
    renderInto(engine, 4800, 240, out);
    EXPECT_NEAR(1.0f, peakAbs(out.mono, 14400 + 480, 4000), 0.01f);
}

// OUT-10: output is audible only while no mute reason is active.
TEST(Mixer, outputIsAudibleOnlyWhenNoMuteReasonIsActive) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    ASSERT_TRUE(engine.send(voiceOn(0, 69, Voice::Sine, 1.0f)));
    Rendered out = renderFrames(engine, 2400, 240);
    EXPECT_GT(peakAbs(out.mono, 1200, 1200), 0.9f);

    ASSERT_TRUE(engine.send(mute(MuteReason::RecordingOnSpeaker, true)));
    ASSERT_TRUE(engine.send(mute(MuteReason::Background, true)));
    renderInto(engine, 2400, 240, out);
    EXPECT_EQ(0.0f, peakAbs(out.mono, 2400 + 480, 1900)) << "silent after the 5 ms ramp";
    EXPECT_FALSE(engine.audible());

    ASSERT_TRUE(engine.send(mute(MuteReason::Background, false)));
    renderInto(engine, 2400, 240, out);
    EXPECT_EQ(0.0f, peakAbs(out.mono, 4800, 2400)) << "one reason still active";

    ASSERT_TRUE(engine.send(mute(MuteReason::RecordingOnSpeaker, false)));
    renderInto(engine, 2400, 240, out);
    EXPECT_GT(peakAbs(out.mono, 7200 + 480, 1900), 0.9f);
    EXPECT_TRUE(engine.audible());
}

// The mix is the same on both channels and clamped to the bus.
TEST(Mixer, stereoCarriesTheSameSignalClampedToUnity) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    for (int slot = 0; slot < kVoiceSlots; ++slot) ASSERT_TRUE(engine.send(voiceOn(slot, 69, Voice::Sine, 1.0f)));
    std::vector<float> buffer(480 * kChannels);
    engine.render(buffer.data(), 480);
    engine.render(buffer.data(), 480);
    float peak = 0.0f;
    for (int i = 0; i < 480; ++i) {
        EXPECT_EQ(buffer[static_cast<std::size_t>(i) * kChannels], buffer[static_cast<std::size_t>(i) * kChannels + 1]);
        peak = std::max(peak, std::fabs(buffer[static_cast<std::size_t>(i) * kChannels]));
    }
    EXPECT_LE(peak, 1.0f);
    EXPECT_GT(peak, 0.99f);
}
