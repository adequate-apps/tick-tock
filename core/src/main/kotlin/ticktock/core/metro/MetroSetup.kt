// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.metro

/** Per-beat accent level; the engine turns these into gain multipliers. */
enum class Accent { ACCENT, NORMAL, SOFT, MUTE }

/** Baked click samples in the engine; there is no asset load. */
enum class ClickSound { WOOD, CLICK, BEEP }

/**
 * Everything the beat generator needs, as one value. The maths lives in the
 * engine; this type only has to be complete and cheap to copy across JNI.
 */
data class MetroSetup(
    val bpm: Int,
    val beatsPerBar: Int,
    /** Note value of one beat: 4 for a quarter, 8 for an eighth. */
    val beatUnit: Int,
    /** True groups eighths in threes, so 6/8 pulses on the dotted quarter. */
    val compoundPulse: Boolean,
    /** One entry per beat in the bar. */
    val accents: List<Accent>,
    /** Clicks per beat: 1 is none, 2 eighths, 3 triplets, 4 sixteenths. */
    val subdivision: Int,
    val click: ClickSound,
    val gain: Float,
) {
    init {
        require(bpm in BPM_MIN..BPM_MAX) { "bpm $bpm outside $BPM_MIN..$BPM_MAX" }
        require(beatsPerBar in 1..BEATS_MAX) { "beatsPerBar $beatsPerBar outside 1..$BEATS_MAX" }
        require(accents.size == beatsPerBar) { "accents has ${accents.size} entries for $beatsPerBar beats" }
        require(subdivision in 1..SUBDIVISION_MAX) { "subdivision $subdivision outside 1..$SUBDIVISION_MAX" }
        require(gain in 0f..1f) { "gain $gain outside 0..1" }
    }

    companion object {
        const val BPM_MIN = 30
        const val BPM_MAX = 300
        const val BEATS_MAX = 16
        const val SUBDIVISION_MAX = 4
    }
}
