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

TEST(OutputController, aRegistrationWhileParkedWaitsForTheRoute) {
    FakeDevice device;
    OutputController c(device);
    c.registerTool(OutputTool::Metronome);
    drainAll(c);
    c.park(ParkReason::HeadphonesDisconnected);
    drainAll(c);
    c.registerTool(OutputTool::Drone);
    EXPECT_TRUE(drainAll(c).empty());
    EXPECT_EQ(1, device.opens) << "the speaker stays silent until the route returns";
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
