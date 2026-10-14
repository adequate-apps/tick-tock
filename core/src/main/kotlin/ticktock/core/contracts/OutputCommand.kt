// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

import ticktock.core.metro.MetroSetup

/** Synthesised timbres. Volume is a gain, never a re-strike (TON-04). */
enum class Voice { SINE, WARM, ROOT_FIFTH }

/**
 * Everything the app can ask the engine to do. Each command maps to one plain
 * struct on the native side, so every field is a number or an enum ordinal.
 * Adding or changing a variant updates the native struct, the fake port and
 * the round-trip test in the same change.
 */
sealed interface OutputCommand {
    /** Registers [tool] as wanting output; the first registration opens the stream (OUT-01). */
    data class Register(val tool: OutputTool) : OutputCommand

    /** Unregisters [tool]; the last unregistration closes the stream. */
    data class Unregister(val tool: OutputTool) : OutputCommand

    /** Starts or retunes a voice in [slot] at [midi] with [voice] at linear [gain]. */
    data class VoiceOn(val slot: Int, val midi: Int, val voice: Voice, val gain: Float) : OutputCommand

    data class VoiceOff(val slot: Int) : OutputCommand

    /** Changes a sounding voice's gain with a short ramp and no re-strike. */
    data class VoiceGain(val slot: Int, val gain: Float) : OutputCommand

    /** Retunes every sounding voice in place at the next block (TON-03). */
    data class SetA4(val hz: Double) : OutputCommand

    /** Bleed duck from the detector: 1.0 is undisturbed, 0.3 is the floor (POL-26). */
    data class Duck(val gain: Float) : OutputCommand

    data class MetroStart(val setup: MetroSetup) : OutputCommand

    /** Live edit; the generator re-anchors from the last beat that sounded (MET-06). */
    data class MetroUpdate(val setup: MetroSetup) : OutputCommand

    data object MetroStop : OutputCommand

    /** Adds or clears one mute reason; output is audible only when none is active (OUT-10). */
    data class Mute(val reason: MuteReason, val active: Boolean) : OutputCommand
}

/** Tools that can hold the output open. */
enum class OutputTool { METRONOME, DRONE, PITCH_PIPE, TEST_TONE }

/** Reasons output may be silenced. One registry owns the set; see OUT-10. */
enum class MuteReason { FOCUS_LOSS, RECORDING_ON_SPEAKER, BACKGROUND }
