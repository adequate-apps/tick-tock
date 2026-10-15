// SPDX-License-Identifier: GPL-3.0-or-later
//
// The registry, the stream policy of decision 13 (SHARED, LOW_LATENCY, rate
// unspecified, two bursts grown to four on xruns), park and resume, and the
// event drain, against a scripted device.
#include "control/OutputController.h"

#include <gtest/gtest.h>

#include <chrono>
#include <vector>

#include "support/FakeDevice.h"
#include "support/Params.h"
#include "support/RenderHarness.h"

using namespace ticktock;
using namespace ticktock::control;
using ticktock::render::OutputTool;
using ticktock::test::FakeDevice;
using ticktock::test::params;

namespace {

constexpr std::uint32_t kMetronome = 1u << 0;
constexpr std::uint32_t kDrone = 1u << 1;

std::vector<ControlEvent> drainAll(OutputController& c) {
    ControlEvent buffer[64];
    std::vector<ControlEvent> out;
    const std::int32_t n = c.drain(buffer, 64);
    for (std::int32_t i = 0; i < n; ++i) out.push_back(buffer[i]);
    return out;
}

// Pulls `frames` frames through the device callback and returns the mono
// signal's click onsets.
std::vector<std::int64_t> pull(FakeDevice& device, std::int32_t frames, std::vector<float>* keep = nullptr) {
    std::vector<float> buffer(static_cast<std::size_t>(device.framesPerBurst) * render::kChannels);
    std::vector<float> mono;
    std::int32_t done = 0;
    while (done < frames) {
        const std::int32_t n = std::min(frames - done, device.framesPerBurst);
        for (float& f : buffer) f = 0.0f;
        device.render(buffer.data(), n);
        for (std::int32_t i = 0; i < n; ++i) mono.push_back(buffer[static_cast<std::size_t>(i) * render::kChannels]);
        done += n;
    }
    if (keep != nullptr) keep->insert(keep->end(), mono.begin(), mono.end());
    return ticktock::test::onsets(mono);
}

}  // namespace

TEST(OutputController, theFirstRegistrationOpensSharedLowLatencyAtTwoBurstsAndTheLastCloses) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    c.registerTool(OutputTool::Drone);
    auto events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Opened, events[0].kind);
    EXPECT_EQ(48000, events[0].a);
    EXPECT_EQ(192, events[0].b);
    EXPECT_EQ(7, events[0].c);
    EXPECT_EQ(8, events[0].d) << "two bursts of 192 at 48 kHz is 8 ms until a timestamp says otherwise";

    ASSERT_EQ(1u, device.requests.size());
    EXPECT_EQ(SharingMode::Shared, device.requests[0].sharing);
    EXPECT_EQ(PerformanceMode::LowLatency, device.requests[0].performance);
    EXPECT_EQ(0, device.requests[0].sampleRate) << "the device chooses the rate";
    ASSERT_EQ(1, device.burstsCount);
    EXPECT_EQ(kStartBursts, device.burstsRecorded[0]);
    EXPECT_EQ(1, device.opens);
    EXPECT_EQ(1, device.starts);
    EXPECT_TRUE(device.running());

    c.unregisterTool(OutputTool::Metronome);
    EXPECT_TRUE(drainAll(c).empty());
    EXPECT_TRUE(device.running());
    c.unregisterTool(OutputTool::Drone);
    events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Closed, events[0].kind);
    EXPECT_EQ(1, device.stops);
    EXPECT_EQ(1, device.closes);
    EXPECT_FALSE(device.running());

    // Unregistering a tool that never registered is not a transition.
    c.unregisterTool(OutputTool::PitchPipe);
    EXPECT_TRUE(drainAll(c).empty());
}

TEST(OutputController, theBufferGrowsOneBurstPerXrunUpToFour) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Drone);
    drainAll(c);
    pull(device, 192 * 4);
    EXPECT_EQ(std::vector<std::int32_t>{2}, device.bufferBurstsSet());

    device.xruns = 1;
    pull(device, 192);
    EXPECT_EQ((std::vector<std::int32_t>{2, 3}), device.bufferBurstsSet());
    pull(device, 192 * 4);
    EXPECT_EQ((std::vector<std::int32_t>{2, 3}), device.bufferBurstsSet()) << "one step per xrun, never per callback";

    device.xruns = 2;
    pull(device, 192);
    EXPECT_EQ((std::vector<std::int32_t>{2, 3, 4}), device.bufferBurstsSet());
    device.xruns = 3;
    pull(device, 192);
    EXPECT_EQ((std::vector<std::int32_t>{2, 3, 4}), device.bufferBurstsSet()) << "four bursts is the ceiling";

    const Diagnostics d = c.diagnostics();
    EXPECT_EQ(4, d.bufferBursts);
    EXPECT_EQ(2, d.growthSteps);
    EXPECT_EQ(3, d.underruns);
    EXPECT_EQ(192 * 11, d.framesRendered);

    auto events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Counters, events[0].kind);
    EXPECT_EQ(0, events[0].a);
    EXPECT_EQ(3, events[0].b);
    EXPECT_TRUE(drainAll(c).empty()) << "counters are reported when they change";
}

// SHM-02: a step counts when the device took it. A stream clamped at two
// bursts refuses every growth, and Diagnostics says two bursts and no steps;
// one that allows three takes one step and refuses the next.
TEST(OutputController, bufferGrowthCountsOnlyTheStepsTheDeviceGranted) {
    FakeDevice device;
    device.maxBursts = 2;
    OutputController c(device);
    c.registerTool(OutputTool::Drone);
    drainAll(c);
    for (int xruns = 1; xruns <= 5; ++xruns) {
        device.xruns = xruns;
        pull(device, 192);
    }
    Diagnostics d = c.diagnostics();
    EXPECT_EQ(2, d.bufferBursts);
    EXPECT_EQ(0, d.growthSteps);
    EXPECT_EQ(5, d.underruns);
    EXPECT_EQ((std::vector<std::int32_t>{2, 3, 3, 3, 3, 3}), device.bufferBurstsSet()) << "asked once per xrun, refused each time";

    device.maxBursts = 3;
    device.xruns = 6;
    pull(device, 192);
    d = c.diagnostics();
    EXPECT_EQ(3, d.bufferBursts);
    EXPECT_EQ(1, d.growthSteps);
    device.xruns = 7;
    pull(device, 192);
    d = c.diagnostics();
    EXPECT_EQ(3, d.bufferBursts);
    EXPECT_EQ(1, d.growthSteps);
    EXPECT_EQ(4, device.bufferBurstsSet().back());

    // A device that errors on the request grows nothing either.
    device.maxBursts = 4;
    device.refuseBufferChanges = true;
    device.xruns = 8;
    pull(device, 192);
    EXPECT_EQ(3, c.diagnostics().bufferBursts);
    EXPECT_EQ(1, c.diagnostics().growthSteps);
}

// Beats wait in the render queue until the event ring has room, so a slow
// drain loses none of them and the drop counter stays honest.
TEST(OutputController, aSlowDrainLosesNoBeats) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    drainAll(c);
    for (int i = 0; i < 1000; ++i) {
        render::BeatEvent beat;
        beat.index = i;
        beat.frame = 100LL * i;
        ASSERT_TRUE(c.engine().beats().tryPush(beat));
    }
    std::vector<ControlEvent> all;
    for (int round = 0; round < 40 && all.size() < 1000; ++round) {
        ControlEvent buffer[64];
        const std::int32_t n = c.drain(buffer, 64);
        for (std::int32_t i = 0; i < n; ++i) all.push_back(buffer[i]);
    }
    ASSERT_EQ(1000u, all.size());
    for (std::size_t i = 0; i < all.size(); ++i) {
        ASSERT_EQ(EventKind::Beat, all[i].kind) << "event " << i;
        ASSERT_EQ(static_cast<std::int64_t>(i), all[i].a) << "event " << i;
    }
    EXPECT_EQ(0, c.diagnostics().droppedBeats);
    EXPECT_EQ(0, c.diagnostics().evictedEvents);
    EXPECT_TRUE(drainAll(c).empty());
}

// GAP-01: the UI needs the actual bar and must distinguish a muted onset
// from a whole silent bar after the generator crosses the JNI boundary.
TEST(OutputController, beatMetadataKeepsSilentBarsSeparateFromMutedOnsets) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    drainAll(c);
    auto setup = params(120, 2);
    setup.accents[0] = render::Accent::Mute;
    setup.barsOn = 1;
    setup.barsOff = 1;
    ASSERT_TRUE(c.metroStart(setup));
    pull(device, 24000 * 6);
    const auto events = drainAll(c);
    ASSERT_EQ(6u, events.size());
    const int flags[] = {0, 1, 2, 2, 0, 1};
    for (std::size_t i = 0; i < events.size(); ++i) {
        const auto& event = events[i];
        ASSERT_EQ(EventKind::Beat, event.kind);
        ASSERT_EQ(4, event.intCount) << "beat " << i;
        EXPECT_EQ(static_cast<int>(i / 2), event.ints[0]);
        EXPECT_EQ(0, event.ints[1]);
        EXPECT_EQ(flags[i], event.ints[2]);
        EXPECT_EQ(0, event.ints[3]);
    }
}

TEST(OutputController, beatMetadataKeepsTheGapBarWhenAMetreEditCutsABarShort) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    drainAll(c);
    auto setup = params(120, 4);
    setup.barsOn = 1;
    setup.barsOff = 1;
    ASSERT_TRUE(c.metroStart(setup));
    pull(device, 30000);
    drainAll(c);
    setup.beatsPerBar = 3;
    ASSERT_TRUE(c.metroUpdate(setup));
    pull(device, 24000);
    const auto events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(2, events[0].a);
    EXPECT_EQ(0, events[0].b);
    ASSERT_EQ(4, events[0].intCount);
    EXPECT_EQ(1, events[0].ints[0]);
    EXPECT_EQ(2, events[0].ints[2]);
}

TEST(OutputController, aDisconnectParksOnTheControlThreadAndResumeReopensAtTheNewRate) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    c.registerTool(OutputTool::Drone);
    ASSERT_TRUE(c.metroStart(params(120)));
    drainAll(c);
    auto clicks = pull(device, 30000);  // beats 0 and 1
    EXPECT_EQ((std::vector<std::int64_t>{0, 24000}), clicks);

    device.disconnect();
    EXPECT_EQ(0, device.closes) << "the error thread closes nothing";
    EXPECT_TRUE(c.hasEvents());
    auto events = drainAll(c);
    EXPECT_EQ(1, device.stops);
    EXPECT_EQ(1, device.closes);
    ASSERT_GE(events.size(), 1u);
    // Two beats, then the park.
    const ControlEvent& parked = events.back();
    EXPECT_EQ(EventKind::Parked, parked.kind);
    EXPECT_EQ(static_cast<std::int64_t>(ParkReason::HeadphonesDisconnected), parked.a);
    EXPECT_EQ(kMetronome | kDrone, parked.tools);
    EXPECT_TRUE(c.diagnostics().parked);

    device.nativeRate = 44100;
    c.resume();
    events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Resumed, events[0].kind);
    EXPECT_EQ(44100, events[0].a);
    EXPECT_EQ(kMetronome | kDrone, events[0].tools);
    EXPECT_EQ(2, device.opens);
    EXPECT_FALSE(c.diagnostics().parked);

    // MET-09 through the whole path: one beat after the stream returned.
    clicks = pull(device, 44100 + 100);
    EXPECT_EQ((std::vector<std::int64_t>{22050, 44100}), clicks);
    events = drainAll(c);
    ASSERT_EQ(2u, events.size());
    EXPECT_EQ(EventKind::Beat, events[0].kind);
    EXPECT_EQ(2, events[0].a);
    EXPECT_EQ(22050, events[0].c);
    EXPECT_EQ(3, events[1].a);
    EXPECT_EQ(44100, events[1].c);
}

TEST(OutputController, anyOtherStreamErrorParksAsOutputFailed) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::PitchPipe);
    drainAll(c);
    device.disconnect(-880);  // AAUDIO_ERROR_TIMEOUT, the Android 11 shape
    auto events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Parked, events[0].kind);
    EXPECT_EQ(static_cast<std::int64_t>(ParkReason::OutputFailed), events[0].a);
    EXPECT_EQ(1u << 2, events[0].tools);
    EXPECT_FALSE(device.running());
}

TEST(OutputController, aToolUnregisteredWhileParkedStaysOffWhenTheRestResume) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    c.registerTool(OutputTool::Drone);
    drainAll(c);

    c.park(ParkReason::IncomingCall);
    auto events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Parked, events[0].kind);
    EXPECT_EQ(static_cast<std::int64_t>(ParkReason::IncomingCall), events[0].a);
    EXPECT_EQ(kMetronome | kDrone, events[0].tools);
    EXPECT_FALSE(device.running());

    c.unregisterTool(OutputTool::Drone);
    EXPECT_TRUE(drainAll(c).empty()) << "still parked, still one tool registered";
    c.park(ParkReason::Background);
    EXPECT_TRUE(drainAll(c).empty()) << "parking twice is one park";

    c.resume();
    events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Resumed, events[0].kind);
    EXPECT_EQ(kMetronome, events[0].tools);
    EXPECT_TRUE(device.running());
    c.resume();
    EXPECT_TRUE(drainAll(c).empty()) << "resume on an open stream is nothing";
}

TEST(OutputController, unregisteringTheLastParkedToolClosesWithoutAStream) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::TestTone);
    drainAll(c);
    c.park(ParkReason::Background);
    drainAll(c);
    EXPECT_EQ(1, device.closes);
    c.unregisterTool(OutputTool::TestTone);
    auto events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Closed, events[0].kind);
    EXPECT_EQ(1, device.closes) << "nothing to close twice";
    EXPECT_FALSE(c.diagnostics().parked);

    // A fresh registration opens again.
    c.registerTool(OutputTool::TestTone);
    events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Opened, events[0].kind);
    EXPECT_EQ(2, device.opens);
}

// The native registry never opens on its own while parked: resume() is the
// one way out of a park, and the control thread decides when to call it.
TEST(OutputController, aRegistrationWhileParkedOpensNothingUntilResume) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    drainAll(c);
    c.park(ParkReason::HeadphonesDisconnected);
    drainAll(c);
    c.registerTool(OutputTool::Drone);
    EXPECT_TRUE(drainAll(c).empty());
    EXPECT_EQ(1, device.opens) << "nothing opens until the control thread resumes";
    c.resume();
    auto events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Resumed, events[0].kind);
    EXPECT_EQ(kMetronome | kDrone, events[0].tools);
}

TEST(OutputController, everyRateRefusedReportsFailedWithTheRefusedRates) {
    FakeDevice device;
    device.refuseAll = true;
    OutputController c(device);
    c.registerTool(OutputTool::Drone);
    auto events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Failed, events[0].kind);
    EXPECT_EQ(7, events[0].a) << "the device id names the device";
    ASSERT_EQ(2, events[0].intCount);
    EXPECT_EQ(48000, events[0].ints[0]);
    EXPECT_EQ(44100, events[0].ints[1]);
    EXPECT_EQ(3, device.opens) << "unspecified, then 48000, then 44100";
    EXPECT_FALSE(device.running());
    EXPECT_TRUE(c.diagnostics().parked) << "the tool stays registered so a retry can resume it";

    device.refuseAll = false;
    device.refuseUnspecified = true;
    c.resume();
    events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Resumed, events[0].kind);
    EXPECT_EQ(48000, events[0].a) << "the first named rate after the unspecified one";
    EXPECT_TRUE(device.running());
}

TEST(OutputController, beatEventsCarryNanosFromTheDeviceTimestamp) {
    FakeDevice device;
    device.timestampValid = true;
    device.tsFrames = 0;
    device.tsNanos = 1000000000LL;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    ASSERT_TRUE(c.metroStart(params(120)));
    drainAll(c);
    pull(device, 48000);
    auto events = drainAll(c);
    ASSERT_EQ(2u, events.size());
    EXPECT_EQ(EventKind::Beat, events[0].kind);
    EXPECT_EQ(0, events[0].a);
    EXPECT_EQ(0, events[0].b);
    EXPECT_EQ(0, events[0].c);
    EXPECT_EQ(1000000000LL, events[0].d);
    EXPECT_EQ(1, events[1].a);
    EXPECT_EQ(1, events[1].b);
    EXPECT_EQ(24000, events[1].c);
    EXPECT_EQ(1500000000LL, events[1].d);
}

TEST(OutputController, latencyComesFromTheTimestampOnceItIsValid) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Drone);
    drainAll(c);
    EXPECT_EQ(8, c.latencyMillis());
    pull(device, 4800);
    device.timestampValid = true;
    device.tsFrames = 4800 - 960;
    device.tsNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
                         std::chrono::steady_clock::now().time_since_epoch()).count();
    const std::int32_t latency = c.latencyMillis();
    EXPECT_GE(latency, 19);
    EXPECT_LE(latency, 21);
}

TEST(OutputController, commandsReachTheRenderThreadInOrder) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Drone);
    drainAll(c);
    ASSERT_TRUE(c.setA4(442.0));
    ASSERT_TRUE(c.voiceOn(1, 69, render::Voice::Sine, 0.8f));
    ASSERT_TRUE(c.voiceGain(1, 0.5f));
    ASSERT_TRUE(c.duck(0.3f));
    ASSERT_TRUE(c.mute(render::MuteReason::Background, true));
    ASSERT_TRUE(c.mute(render::MuteReason::Background, false));
    std::vector<float> mono;
    pull(device, 4800, &mono);
    EXPECT_EQ(1, c.engine().voices().activeCount());
    EXPECT_NEAR(442.0, c.engine().voices().frequencyOf(1), 1e-9);
    float peak = 0.0f;
    for (std::size_t i = 2400; i < mono.size(); ++i) peak = std::max(peak, std::fabs(mono[i]));
    EXPECT_NEAR(0.5f * 0.3f, peak, 0.01f);
    ASSERT_TRUE(c.voiceOff(1));
    ASSERT_TRUE(c.metroStop());
    pull(device, 480);
    EXPECT_EQ(0, c.engine().voices().activeCount());
    EXPECT_EQ(0, c.diagnostics().droppedCommands);
}

TEST(OutputController, aLateBeatShowsUpInCounters) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    ASSERT_TRUE(c.metroStart(params(60)));
    drainAll(c);
    pull(device, 40000);
    ASSERT_TRUE(c.metroUpdate(params(120)));
    pull(device, 20000);
    auto events = drainAll(c);
    ASSERT_GE(events.size(), 1u);
    EXPECT_EQ(EventKind::Counters, events.back().kind);
    EXPECT_EQ(1, events.back().a);
    EXPECT_EQ(0, events.back().b);
    EXPECT_EQ(1, c.diagnostics().lateBeatsDropped);
}

// MET-09, OUT-13: what the control thread replays into a resumed engine
// disturbs nothing. voiceOn on the sounding slot keeps its phase, so the
// drone continues where it left off instead of striking again; metroUpdate
// with the setup the generator already runs keeps the next click one period
// after the first frame of the new stream, and the beat count continues.
TEST(OutputController, theReplayIntoAResumedEngineDisturbsNeitherTheVoiceNorTheBeat) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    c.registerTool(OutputTool::Drone);
    ASSERT_TRUE(c.metroStart(params(120)));
    ASSERT_TRUE(c.voiceOn(0, 62, render::Voice::Sine, 0.3f));
    drainAll(c);
    const std::int32_t before = 30000;  // beats 0 and 1; the voice is 183.5 cycles in
    pull(device, before);
    drainAll(c);

    c.park(ParkReason::HeadphonesDisconnected);
    drainAll(c);
    c.resume();
    auto events = drainAll(c);
    ASSERT_EQ(1u, events.size());
    EXPECT_EQ(EventKind::Resumed, events[0].kind);

    // The replay, as the control thread sends it into the continued engine.
    ASSERT_TRUE(c.voiceOn(0, 62, render::Voice::Sine, 0.3f));
    ASSERT_TRUE(c.metroUpdate(params(120)));

    std::vector<float> mono;
    pull(device, 48000 + 100, &mono);
    events = drainAll(c);
    ASSERT_EQ(2u, events.size());
    EXPECT_EQ(EventKind::Beat, events[0].kind);
    EXPECT_EQ(2, events[0].a) << "the beat count continues";
    EXPECT_EQ(2, events[0].b) << "the bar position continues";
    EXPECT_EQ(24000, events[0].c) << "one beat after the new stream's first frame, never at once";
    EXPECT_EQ(3, events[1].a);
    EXPECT_EQ(48000, events[1].c);

    // The drone sounds throughout, so the clicks are read off the signal
    // against it: the sine the old stream was playing, phase and all, so that
    // sample i of the new stream is sample `before + i` of one continuous
    // tone. A re-strike would start from phase zero under a gain ramp. The
    // stream's own fade-in covers the first 5 ms.
    const double hz = ticktock::test::midiHz(62);
    const double increment = 2.0 * M_PI * hz / 48000.0;
    auto drone = [&](std::int32_t i) { return 0.3 * std::sin(increment * (before + i)); };
    for (std::int32_t i = 0; i < 1440; ++i) {
        const double gate = i < 240 ? (i + 1) / 240.0 : 1.0;
        ASSERT_NEAR(drone(i) * gate, mono[static_cast<std::size_t>(i)], 2e-3) << "frame " << i << ": no click at once";
    }
    for (std::int32_t i = 1440; i < 24000; ++i) {
        ASSERT_NEAR(drone(i), mono[static_cast<std::size_t>(i)], 2e-3) << "frame " << i;
    }
    EXPECT_NEAR(drone(24000) + render::kWoodData[0] * 0.7, mono[24000], 2e-3) << "the click lands on beat 2's frame";
}

// A metronome started while the tools were parked reaches the resumed
// engine as an edit too; a generator that was not running starts from it
// and clicks at once, as a fresh start does.
TEST(OutputController, aMetronomeStartedWhileParkedClicksAtOnceOnResume) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Drone);
    drainAll(c);
    pull(device, 4800);
    c.park(ParkReason::HeadphonesDisconnected);
    drainAll(c);
    c.resume();
    drainAll(c);
    ASSERT_TRUE(c.metroUpdate(params(120)));
    const auto clicks = pull(device, 24000 + 100);
    EXPECT_EQ((std::vector<std::int64_t>{0, 24000}), clicks);
}
