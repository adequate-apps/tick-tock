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
    /**
     * Gap bars (GAP-01): [barsOn] bars sound, then [barsOff] bars are silent,
     * repeating. The beat row keeps moving through a silent bar and outlines
     * it. The defaults sound every bar.
     */
    val barsOn: Int = 1,
    val barsOff: Int = 0,
) {
    init {
        require(bpm in BPM_MIN..BPM_MAX) { "bpm $bpm outside $BPM_MIN..$BPM_MAX" }
        require(beatsPerBar in 1..BEATS_MAX) { "beatsPerBar $beatsPerBar outside 1..$BEATS_MAX" }
        require(accents.size == beatsPerBar) { "accents has ${accents.size} entries for $beatsPerBar beats" }
        require(subdivision in 1..SUBDIVISION_MAX) { "subdivision $subdivision outside 1..$SUBDIVISION_MAX" }
        require(gain in 0f..1f) { "gain $gain outside 0..1" }
        require(barsOn in 1..GAP_BARS_MAX) { "barsOn $barsOn outside 1..$GAP_BARS_MAX" }
        require(barsOff in 0..GAP_BARS_MAX) { "barsOff $barsOff outside 0..$GAP_BARS_MAX" }
    }

    /** Length of one on/off cycle in bars. */
    val cycleBars: Int get() = barsOn + barsOff

    /**
     * Whether zero-based [bar] sounds. This is the one statement of the gap
     * rule; the engine's generator and the beat row both follow it, and a
     * tempo change keeps the bar position (GAP-02) because the index counts
     * bars, never frames.
     */
    fun barSounds(bar: Int): Boolean {
        require(bar >= 0) { "bar $bar is negative" }
        return bar % cycleBars < barsOn
    }

    companion object {
        const val BPM_MIN = 30
        const val BPM_MAX = 300
        const val BEATS_MAX = 16
        const val SUBDIVISION_MAX = 4
        const val GAP_BARS_MAX = 16
    }
}
