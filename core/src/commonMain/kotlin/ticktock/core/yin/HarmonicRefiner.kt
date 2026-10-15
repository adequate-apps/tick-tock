// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.yin

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * The last step of pitch estimation: YIN names the period, this finds the
 * fundamental within a few cents of it that best explains the whole window.
 *
 * The score of a candidate f is the energy of the Tukey-windowed signal at
 * f, 2f, 3f and so on up to [MAX_HARMONICS] or [MAX_HARMONIC_HZ]: the
 * maximum-likelihood pitch of a harmonic tone in white noise. YIN works from
 * the time-domain difference function, whose minimum wanders by a cent or
 * two once breath or room noise is 20 dB under the tone; the harmonic score
 * averages that noise over every partial and every sample of the window, so
 * the reading holds still (the simulated play tests measure both).
 *
 * The search looks at five points across [SEARCH_SPAN] either side of YIN's
 * estimate, then settles the peak with parabolas through the log score.
 * Inside that range the score has one peak: the narrowest lobe it ever sees,
 * the top harmonic of the lowest note, spans about 15 c either side. When the
 * best point is the range's edge the refinement found nothing better than
 * YIN, and YIN's answer stands. YIN alone decides the note.
 *
 * Below about 100 Hz the window holds under ten cycles, and leakage between
 * neighbouring partials leaves the peak up to about 0.1 c from the true
 * fundamental: the floor of a 93 ms window, the same with or without noise.
 *
 * One refiner serves one sample rate and window length. Not thread safe: the
 * analysis thread owns it.
 */
class HarmonicRefiner(private val sampleRate: Int, private val frames: Int) {
    // A Tukey window: flat over the middle so every sample counts equally, as it
    // does in the pitch the window reports, with cosine tapers at the ends to
    // keep one partial's leakage off the next.
    private val taper = DoubleArray(frames) { i ->
        val edge = TAPER * (frames - 1) / 2.0
        when {
            i < edge -> 0.5 - 0.5 * cos(PI * i / edge)
            i > frames - 1 - edge -> 0.5 - 0.5 * cos(PI * (frames - 1 - i) / edge)
            else -> 1.0
        }
    }
    private val signal = DoubleArray(frames)
    private val grid = DoubleArray(GRID.size)
    private val fine = DoubleArray(3)
    private val trial = DoubleArray(GRID.size)
    private val coefficient = DoubleArray(GRID.size * MAX_HARMONICS)
    private val s1 = DoubleArray(GRID.size * MAX_HARMONICS)
    private val s2 = DoubleArray(GRID.size * MAX_HARMONICS)

    /** The refined fundamental near [hz] for the first [frames] samples of [window]; [hz] itself when no better one is found. */
    fun refine(window: FloatArray, hz: Double): Double {
        if (hz <= 0.0 || !hz.isFinite()) return hz
        // Remove the window's DC so a microphone offset adds nothing at low harmonics.
        var mean = 0.0
        for (i in 0 until frames) mean += window[i]
        mean /= frames
        for (i in 0 until frames) signal[i] = (window[i] - mean) * taper[i]
        val harmonics = min(MAX_HARMONICS, (min(MAX_HARMONIC_HZ, sampleRate * 0.45) / hz).toInt()).coerceAtLeast(1)

        // A coarse look across the range, then parabolas through the log score,
        // whose peak is close to a parabola's: eight scores in two passes.
        for (g in GRID.indices) trial[g] = hz * ratio(GRID[g])
        scores(trial, GRID.size, harmonics, grid)
        var best = 0
        for (g in GRID.indices) {
            grid[g] = ln(grid[g])
            if (grid[g] > grid[best]) best = g
        }
        // A peak at the edge of the range is no peak: keep YIN's answer.
        if (best == 0 || best == GRID.size - 1) return hz
        var offset = GRID[best] + vertex(grid[best - 1], grid[best], grid[best + 1]) * GRID_STEP
        for (step in FINE_STEPS) {
            trial[0] = hz * ratio(offset - step)
            trial[1] = hz * ratio(offset)
            trial[2] = hz * ratio(offset + step)
            scores(trial, 3, harmonics, fine)
            offset += vertex(ln(fine[0]), ln(fine[1]), ln(fine[2])) * step
        }
        return if (offset.isFinite() && kotlin.math.abs(offset) < SEARCH_SPAN) hz * ratio(offset) else hz
    }

    /** Where the parabola through three equally spaced points peaks, in steps from the middle one. */
    private fun vertex(left: Double, middle: Double, right: Double): Double {
        val curve = left - 2.0 * middle + right
        return if (curve < 0.0) (0.5 * (left - right) / curve).coerceIn(-1.0, 1.0) else 0.0
    }

    /**
     * The energy of the windowed signal at the first [harmonics] multiples of
     * each of the first [count] frequencies in [f0s], into [out]. Each partial
     * is a Goertzel recurrence (one multiply and two adds per sample); all of
     * them run side by side in one pass over the window, so the processor
     * overlaps dozens of independent chains instead of waiting on one.
     */
    private fun scores(f0s: DoubleArray, count: Int, harmonics: Int, out: DoubleArray) {
        val chains = count * harmonics
        for (c in 0 until count) {
            for (k in 1..harmonics) coefficient[c * harmonics + k - 1] = 2.0 * cos(2.0 * PI * f0s[c] * k / sampleRate)
        }
        s1.fill(0.0, 0, chains)
        s2.fill(0.0, 0, chains)
        for (i in 0 until frames) {
            val x = signal[i]
            for (j in 0 until chains) {
                val s0 = x + coefficient[j] * s1[j] - s2[j]
                s2[j] = s1[j]
                s1[j] = s0
            }
        }
        for (c in 0 until count) {
            var total = 0.0
            for (k in 0 until harmonics) {
                val j = c * harmonics + k
                total += s1[j] * s1[j] + s2[j] * s2[j] - coefficient[j] * s1[j] * s2[j]
            }
            out[c] = total
        }
    }

    private fun ratio(cents: Double): Double = 2.0.pow(cents / 1200.0)

    companion object {
        /** How far from YIN's estimate the refinement looks, in cents either side. YIN on a clean tone is within a cent. */
        const val SEARCH_SPAN = 7.0

        /** The share of the window given to the two tapers together. */
        private const val TAPER = 0.3

        /** Partials past this carry little of a wind instrument's energy and only cost time. */
        const val MAX_HARMONICS = 12
        const val MAX_HARMONIC_HZ = 4_000.0

        /**
         * The coarse look: five scores, 3 c apart. Vibrato can put a window's
         * pitch 3 c or more from YIN's; the range must hold it, or the note's
         * readings come from two estimators and its mean drifts.
         */
        private val GRID = doubleArrayOf(-6.0, -3.0, 0.0, 3.0, 6.0)
        private const val GRID_STEP = 3.0

        /** The fine parabolas' spacing, in cents. A finer second pass moves nothing measurable. */
        private val FINE_STEPS = doubleArrayOf(0.3)
    }
}
