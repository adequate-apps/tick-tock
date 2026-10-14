// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.config

import ticktock.core.json.JsonValue
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
enum class Spelling(val wire: String) { AUTO("auto"), SHARPS("sharps"), FLATS("flats") }
enum class ResponseMode(val wire: String) { FAST("fast"), NORMAL("normal"), SLOW("slow") }
enum class DroneVoice(val wire: String) { SINE("sine"), WARM("warm"), ROOT_FIFTH("root-fifth") }
enum class ClickSound(val wire: String) { WOOD("wood"), CLICK("click"), BEEP("beep") }

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
    val droneVoice: DroneVoice? = null,
    val clickSound: ClickSound? = null,
    /** Metronome visual offset in milliseconds; positive draws the beat later. */
    val visualOffsetMs: Int? = null,
    /** Settings fields this version does not know, preserved as read. */
    val unknownFields: Map<String, JsonValue> = emptyMap(),
) {
    companion object {
        val A4_HZ: ClosedFloatingPointRange<Double> = 430.0..450.0
        val NOISE_FLOOR_DB: IntRange = -80..-10
        val VISUAL_OFFSET_MS: IntRange = -500..500
    }
}
