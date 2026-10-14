// SPDX-License-Identifier: GPL-3.0-or-later
//
// OUT-16: the render callback never touches the heap. Linked only into the
// alloc variant, where operator new counts.
#include "control/OutputController.h"
#include "render/RenderEngine.h"

#include <gtest/gtest.h>

#include <vector>

#include "support/AllocCounter.h"
#include "support/FakeDevice.h"
#include "support/Params.h"

using namespace ticktock;
using namespace ticktock::render;
using ticktock::test::CountAllocations;
using ticktock::test::FakeDevice;
using ticktock::test::params;

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

}  // namespace

TEST(Allocation, theCounterSeesAnAllocation) {
    CountAllocations counting;
    std::vector<int>* v = new std::vector<int>(10);
    delete v;
    EXPECT_GE(counting.count(), 1);
}

TEST(Allocation, renderNeverAllocatesUnderEveryCommand) {
    RenderEngine engine;
    engine.onStreamStart(48000.0);
    std::vector<float> buffer(960 * kChannels);
    MetroParams metro = params(300, 7, 4, 0.9f);
    metro.accents[1] = Accent::Mute;
    metro.barsOn = 2;
    metro.barsOff = 1;

    CountAllocations counting;
    for (int slot = 0; slot < kVoiceSlots; ++slot) {
        ASSERT_TRUE(engine.send(voiceOn(slot, 50 + slot * 7, static_cast<Voice>(slot % 3), 0.5f)));
    }
    Command start;
    start.kind = CommandKind::MetroStart;
    start.metro = metro;
    ASSERT_TRUE(engine.send(start));
    for (int block = 0; block < 500; ++block) {  // ten seconds of audio
        if (block % 50 == 10) {
            Command a4;
            a4.kind = CommandKind::SetA4;
            a4.a4 = 440.0 + block / 50;
            ASSERT_TRUE(engine.send(a4));
        }
        if (block % 50 == 20) {
            Command upd;
            upd.kind = CommandKind::MetroUpdate;
            upd.metro = metro;
            upd.metro.bpm = 200 + block / 5;
            ASSERT_TRUE(engine.send(upd));
        }
        if (block % 50 == 30) {
            Command m;
            m.kind = CommandKind::Mute;
            m.muteReason = MuteReason::Background;
            m.active = (block / 50) % 2 == 0;
            ASSERT_TRUE(engine.send(m));
        }
        if (block % 50 == 40) {
            Command d;
            d.kind = CommandKind::Duck;
            d.gain = 0.3f;
            ASSERT_TRUE(engine.send(d));
        }
        engine.render(buffer.data(), 960);
        BeatEvent beat;
        while (engine.beats().tryPop(beat)) {}
    }
    engine.onStreamStart(44100.0);
    for (int block = 0; block < 50; ++block) engine.render(buffer.data(), 960);
    // A block longer than one slice renders in slices, still without the heap.
    std::vector<float> big(static_cast<std::size_t>(kMaxSlice + 1000) * kChannels);
    {
        CountAllocations inner;
        engine.render(big.data(), kMaxSlice + 1000);
        EXPECT_EQ(0, inner.count());
    }
    EXPECT_EQ(0, counting.count()) << "render() reached the heap";
}

TEST(Allocation, theDeviceCallbackPathNeverAllocates) {
    FakeDevice device;
    control::OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    ASSERT_TRUE(c.metroStart(params(240, 4, 2)));
    std::vector<float> buffer(static_cast<std::size_t>(device.framesPerBurst) * kChannels);
    CountAllocations counting;
    for (int i = 0; i < 2000; ++i) {
        if (i == 500) device.xruns = 1;
        if (i == 1000) device.xruns = 2;
        device.render(buffer.data(), device.framesPerBurst);
    }
    device.disconnect();
    EXPECT_EQ(0, counting.count()) << "onRender or onError reached the heap";
}
