// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import ticktock.core.contracts.InputSource
import kotlin.math.max
import kotlin.math.pow

/**
 * How quickly the readout commits to a note. The three presets are rows of
 * [ResponseModeTable]; [wire] is the spelling in the prefs file and the
 * personal configuration.
 */
enum class ResponseMode(val wire: String) {
    FAST("fast"),
    NORMAL("normal"),
    SLOW("slow");

    companion object {
        val DEFAULT = NORMAL

        fun fromWire(text: String?): ResponseMode? = entries.firstOrNull { it.wire == text }

        /**
         * A stored mode this build does not know becomes [DEFAULT] (POL-04),
         * so a prefs file written by a later version still loads.
         */
        fun fromStored(text: String?): ResponseMode = fromWire(text) ?: DEFAULT
    }
}

/**
 * One row of the response table (spec 2.1). Hops are the only unit of time
 * (POL-09): nothing here is a millisecond.
 */
data class ModeParams(
    /** How many recent classified hops count as votes; nothing older exists (POL-03). */
    val voteWindow: Int,
    /** Votes for one note inside the window that make it win. */
    val confirm: Int,
    /** Readings dropped at each end of a note (POL-06, POL-07). */
    val edge: Int,
    /** A candidate above this is noise, on every path (POL-10). */
    val aperiodicityCeiling: Double,
    /** Linear RMS, full scale 1, below which a hop is silence under UNPROCESSED (POL-11). */
    val rmsFloor: Double,
) {
    init {
        // Every mode confirms on more than half the window, so two notes can
        // never both hold a winning count at once.
        require(confirm * 2 > voteWindow) { "confirm $confirm must be a majority of the window $voteWindow" }
        require(edge >= 0 && confirm >= 1)
    }

    /** Hop index of the first saved reading after a tone starts at hop 0 (POL-01). */
    val firstReadingHops: Int get() = confirm + 2 * edge
}

/** The response-mode table (spec 2.1), the only place these figures live. */
object ResponseModeTable {
    private val FAST = ModeParams(voteWindow = 3, confirm = 2, edge = 1, aperiodicityCeiling = 0.15, rmsFloor = 8e-5)
    private val NORMAL = ModeParams(voteWindow = 5, confirm = 3, edge = 2, aperiodicityCeiling = 0.10, rmsFloor = 1.5e-4)
    private val SLOW = ModeParams(voteWindow = 10, confirm = 6, edge = 4, aperiodicityCeiling = 0.07, rmsFloor = 3e-4)

    fun of(mode: ResponseMode): ModeParams = when (mode) {
        ResponseMode.FAST -> FAST
        ResponseMode.NORMAL -> NORMAL
        ResponseMode.SLOW -> SLOW
    }
}

/**
 * Level differences between capture presets (SRC-03). The table's floors are
 * stated for UNPROCESSED, which the platform specifies with no gain or
 * processing. VOICE_RECOGNITION and MIC deliver the same sound pressure about
 * 18 dB hotter, so their floors sit 18 dB higher and a note that just passes
 * the gate on one preset just passes it on the other. The fake input plays
 * fixtures synthesised on the UNPROCESSED scale.
 */
object SourceLevel {
    /** Initial estimate; a device measurement in M2 may refine it. */
    const val UNPROCESSED_RELATIVE_DB = -18.0

    /** How much louder [source] delivers a given sound pressure than UNPROCESSED, in dB. */
    fun offsetDb(source: InputSource): Double = when (source) {
        InputSource.UNPROCESSED, InputSource.FAKE -> 0.0
        InputSource.VOICE_RECOGNITION, InputSource.MIC -> -UNPROCESSED_RELATIVE_DB
    }

    fun linearFactor(source: InputSource): Double = dbToLinear(offsetDb(source))

    fun dbToLinear(db: Double): Double = 10.0.pow(db / DB_PER_DECADE_OF_AMPLITUDE)

    private const val DB_PER_DECADE_OF_AMPLITUDE = 20.0
}

/** The silence gate's threshold for one mode, preset and player setting. */
object Gate {
    /**
     * Linear RMS below which a hop is silence. [noiseFloorDb] is the player's
     * "ignore sounds quieter than" setting in dBFS on the UNPROCESSED scale;
     * whichever of it and the mode's floor is higher wins, and both shift with
     * the preset's offset.
     */
    fun floor(params: ModeParams, source: InputSource, noiseFloorDb: Int? = null): Double {
        val player = noiseFloorDb?.let { SourceLevel.dbToLinear(it.toDouble()) } ?: 0.0
        return max(params.rmsFloor, player) * SourceLevel.linearFactor(source)
    }
}
