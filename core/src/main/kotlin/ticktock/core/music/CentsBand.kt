// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import kotlin.math.abs
import kotlin.math.floor

/**
 * Whole-cent display value (BND-01, BND-05, BND-06). Every readout, table cell
 * and export gets its digits from [shown], so the number a player sees is the
 * same everywhere, and the colour is judged on that number (BND-03).
 */
object Cents {
    /**
     * The whole cent the UI prints, or null when there is no reading: a cent
     * value that is NaN or infinite, or a frequency that is not positive.
     * Exact halves round away from zero, so +5.5 shows as +6 and -5.5 as -6.
     */
    fun shown(cents: Double, hz: Double = 1.0): Int? {
        if (cents.isNaN() || cents.isInfinite()) return null
        if (hz.isNaN() || hz <= 0.0) return null
        return roundHalfAwayFromZero(cents)
    }

    /** "+0", "-13", "+5", or an en dash when there is no reading. The sign is always present. */
    fun format(shown: Int?): String = when {
        shown == null -> NO_READING
        shown < 0 -> shown.toString()
        else -> "+$shown"
    }

    const val NO_READING = "–"

    private fun roundHalfAwayFromZero(value: Double): Int {
        val magnitude = floor(abs(value) + 0.5)
        return if (value < 0) (-magnitude).toInt() else magnitude.toInt()
    }
}

/** Colour meaning of a shown cent value. Green, amber and red carry only this meaning. */
enum class CentsBand {
    IN_TUNE,
    CLOSE,
    OFF;

    companion object {
        /** Widest |shown| that counts as in tune (BND-02). The only threshold literals live here. */
        const val IN_TUNE_MAX = 5

        /** Widest |shown| that counts as close (BND-02). */
        const val CLOSE_MAX = 12

        /** Band for the integer the UI prints; null (no reading) is [OFF] (BND-05). */
        fun of(shown: Int?): CentsBand = when {
            shown == null -> OFF
            abs(shown) <= IN_TUNE_MAX -> IN_TUNE
            abs(shown) <= CLOSE_MAX -> CLOSE
            else -> OFF
        }
    }
}
