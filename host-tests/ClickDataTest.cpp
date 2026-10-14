// SPDX-License-Identifier: GPL-3.0-or-later
#include "render/Clicks.h"

#include <gtest/gtest.h>

#include <cmath>
#include <vector>

using namespace ticktock::render;

namespace {
const float* kAll[] = {kWoodData, kClickData, kBeepData};
}

TEST(ClickData, everyClickAttacksOnItsFirstSampleAndEndsInSilence) {
    for (const float* data : kAll) {
        EXPECT_NE(0.0f, data[0]) << "the onset frame carries the attack (MET-03)";
        EXPECT_EQ(0.0f, data[kClickFrames - 1]);
        float peak = 0.0f;
        for (int i = 0; i < kClickFrames; ++i) peak = std::max(peak, std::fabs(data[i]));
        EXPECT_LE(peak, 0.9f + 1e-6f);
        EXPECT_GE(peak, 0.5f);
    }
    EXPECT_EQ(1440, kClickFrames);
    EXPECT_DOUBLE_EQ(48000.0, kClickRate);
}

TEST(ClickPlayer, keepsItsLengthAtAnotherRate) {
    ClickPlayer player;
    player.prepare(44100.0);
    player.trigger(ClickSound::Beep, 1.0f, 0);
    std::vector<float> mono(4000, 0.0f);
    player.render(mono.data(), 4000);
    int last = -1;
    for (int i = 0; i < 4000; ++i) {
        if (mono[static_cast<std::size_t>(i)] != 0.0f) last = i;
    }
    // 30 ms at 44.1 kHz is 1323 frames; the fade-out zeros the final ones.
    EXPECT_NEAR(1323, last, 100);
    EXPECT_LT(last, 1400);
    EXPECT_NE(0.0f, mono[0]);
    EXPECT_EQ(0, player.activeCount());
}

TEST(ClickPlayer, startsAtTheRequestedOffsetAndScalesByGain) {
    ClickPlayer player;
    player.prepare(48000.0);
    player.trigger(ClickSound::Wood, 0.5f, 100);
    std::vector<float> mono(256, 0.0f);
    player.render(mono.data(), 256);
    for (int i = 0; i < 100; ++i) EXPECT_EQ(0.0f, mono[static_cast<std::size_t>(i)]);
    EXPECT_FLOAT_EQ(kWoodData[0] * 0.5f, mono[100]);
    EXPECT_FLOAT_EQ(kWoodData[1] * 0.5f, mono[101]);
}
