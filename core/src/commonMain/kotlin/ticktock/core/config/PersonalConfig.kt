// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.config

import ticktock.core.contracts.Voice
import ticktock.core.detect.ResponseMode
import ticktock.core.json.JsonValue
import ticktock.core.metro.ClickSound
import ticktock.core.music.Accidentals
import ticktock.core.music.Pitch
import ticktock.core.ranges.UserRangeOverride

/**
 * The portable personal configuration (owner decision 3): everything a player
 * carries to another device. The file format is documented in
 * docs/personal-config.md; this is its in-memory shape. Every field is optional
 * so a file that carries only one override is still a complete, valid file.
 */
data class PersonalConfig(
    /** Keyed-range overrides by catalogue key. */
    val rangeOverrides: Map<String, UserRangeOverride> = emptyMap(),
    val hornProfiles: List<HornProfile> = emptyList(),
    val settings: PortableSettings = PortableSettings(),
    /** Top-level fields this version does not know, kept so they survive a round trip. */
    val unknownFields: Map<String, JsonValue> = emptyMap(),
) {
    companion object {
        const val FORMAT = "ticktock-personal-config"
        const val VERSION = 1
    }
}

/** A named horn: one instrument plus the player's nickname for it, so history can be filtered by horn. */
data class HornProfile(val id: String, val instrumentKey: String, val nickname: String)

enum class DisplayMode(val wire: String) { WRITTEN("written"), CONCERT("concert") }
/**
 * The player's accidental choice as stored. [accidentals] is the override the
 * resolver takes ([ticktock.core.music.NoteSpelling.resolve]); AUTO is no
 * override, so the instrument's family decides.
 */
enum class Spelling(val wire: String, val accidentals: Accidentals?) {
    AUTO("auto", null),
    SHARPS("sharps", Accidentals.SHARPS),
    FLATS("flats", Accidentals.FLATS),
}

/**
 * File spellings of the engine's own enums. The config layer names the voices
 * and clicks the engine plays, so it reuses those types rather than keeping
 * a second list that could drift from them. The response mode is the
 * detector's [ResponseMode] for the same reason.
 */
val Voice.wire: String
    get() = when (this) {
        Voice.SINE -> "sine"
        Voice.WARM -> "warm"
        Voice.ROOT_FIFTH -> "root-fifth"
    }

val ClickSound.wire: String
    get() = when (this) {
        ClickSound.WOOD -> "wood"
        ClickSound.CLICK -> "click"
        ClickSound.BEEP -> "beep"
    }

/**
 * Settings worth carrying between devices. Null means "the file says nothing
 * about this", which an import leaves untouched. Device-bound state (permission
 * grants, measured latency, diagnostics counters) is deliberately absent.
 */
data class PortableSettings(
    val instrumentKey: String? = null,
    val a4Hz: Double? = null,
    val displayMode: DisplayMode? = null,
    val spelling: Spelling? = null,
    val responseMode: ResponseMode? = null,
    /** "Ignore sounds quieter than", in dBFS. */
    val noiseFloorDb: Int? = null,
    val keepAwake: Boolean? = null,
    /** EAR-03: the readout hides while a note sounds. */
    val earsFirst: Boolean? = null,
    /** TLK-02: settled notes are read by a polite live region. */
    val announceSettledNotes: Boolean? = null,
    val droneVoice: Voice? = null,
    val clickSound: ClickSound? = null,
    /** Metronome visual offset in milliseconds; positive draws the beat later. */
    val visualOffsetMs: Int? = null,
    /** Settings fields this version does not know, preserved as read. */
    val unknownFields: Map<String, JsonValue> = emptyMap(),
) {
    companion object {
        /** Decision 16; the one definition lives in [Pitch]. */
        val A4_HZ: ClosedFloatingPointRange<Double> = Pitch.A4_HZ
        val NOISE_FLOOR_DB: IntRange = -80..-10
        val VISUAL_OFFSET_MS: IntRange = -500..500
    }
}
