// SPDX-License-Identifier: GPL-3.0-or-later
//
// The plain structs that cross from the control thread into the render
// callback and back. Every enum here mirrors the ordinal order of its Kotlin
// counterpart in core/contracts (OutputCommand.kt, OutputEvent.kt) and
// core/metro/MetroSetup.kt, so the JNI bridge can pass ordinals through
// untouched. Changing one of those enums changes this file in the same PR.
#pragma once

#include <cstdint>

namespace ticktock::render {

// ticktock.core.contracts.Voice
enum class Voice : std::int32_t { Sine = 0, Warm = 1, RootFifth = 2 };

// ticktock.core.metro.Accent
enum class Accent : std::int32_t { Accent = 0, Normal = 1, Soft = 2, Mute = 3 };

// ticktock.core.metro.ClickSound
enum class ClickSound : std::int32_t { Wood = 0, Click = 1, Beep = 2 };

// ticktock.core.contracts.OutputTool; also the bit index in a tool mask.
enum class OutputTool : std::int32_t { Metronome = 0, Drone = 1, PitchPipe = 2, TestTone = 3 };
constexpr std::int32_t kToolCount = 4;

// ticktock.core.contracts.MuteReason; also the bit index in the mute mask.
enum class MuteReason : std::int32_t { RecordingOnSpeaker = 0, Background = 1 };

// Bounds copied from MetroSetup's companion; the Kotlin side validates, the
// generator clamps so a bad struct can never index out of range.
constexpr std::int32_t kBpmMin = 30;
constexpr std::int32_t kBpmMax = 300;
constexpr std::int32_t kBeatsMax = 16;
constexpr std::int32_t kSubdivisionMax = 4;
constexpr std::int32_t kGapBarsMax = 16;

// The stream is interleaved stereo with the same signal on both channels.
constexpr std::int32_t kChannels = 2;

// Voice slots. The contract numbers slots freely; the engine offers this many.
constexpr std::int32_t kVoiceSlots = 4;

// ticktock.core.metro.MetroSetup as plain data.
struct MetroParams {
    std::int32_t bpm = 120;
    std::int32_t beatsPerBar = 4;
    std::int32_t beatUnit = 4;
    bool compoundPulse = false;
    Accent accents[kBeatsMax] = {};
    std::int32_t subdivision = 1;
    ClickSound click = ClickSound::Wood;
    float gain = 1.0f;
    std::int32_t barsOn = 1;
    std::int32_t barsOff = 0;
    // Runtime calibration bound and the identity echoed by every beat.
    std::int32_t beatLimit = 0;
    std::int32_t generation = 0;

    // The one statement of the gap rule on this side of the wall; mirrors
    // MetroSetup.barSounds so the beat row and the clicks agree (GAP-01).
    bool barSounds(std::int64_t bar) const {
        const std::int64_t cycle = barsOn + barsOff;
        return cycle <= 0 || (bar % cycle) < barsOn;
    }
};

// The render-side subset of OutputCommand. Register and Unregister never
// reach the callback: they open and close the stream on the control thread.
enum class CommandKind : std::int32_t {
    VoiceOn,
    VoiceOff,
    VoiceGain,
    SetA4,
    Duck,
    MetroStart,
    MetroUpdate,
    MetroStop,
    Mute,
};

struct Command {
    CommandKind kind = CommandKind::MetroStop;
    std::int32_t slot = 0;
    std::int32_t midi = 69;
    Voice voice = Voice::Sine;
    float gain = 0.0f;
    double a4 = 440.0;
    MuteReason muteReason = MuteReason::RecordingOnSpeaker;
    bool active = false;
    MetroParams metro{};
};

// What the callback reports: one struct per beat onset, in stream frames.
// The control side adds the wall-clock mapping (OutputEvent.Beat.nanos).
struct BeatEvent {
    std::int64_t index = 0;
    std::int32_t beatInBar = 0;
    std::int64_t frame = 0;
    bool audible = true;
    std::int64_t bar = 0;
    bool silentBar = false;
    std::int32_t generation = 0;
};

}  // namespace ticktock::render
