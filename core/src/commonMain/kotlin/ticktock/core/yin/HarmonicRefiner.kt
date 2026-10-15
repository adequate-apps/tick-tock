// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.yin

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The last step of pitch estimation: YIN names the period, this finds the
 * fundamental within a few cents of it that best explains the whole window.
 *
 * The score of a candidate f is how much of the Tukey-windowed signal a
 * real sinusoid at each of f, 2f, 3f and so on, up to [MAX_HARMONICS] or
 * [MAX_HARMONIC_HZ], explains by least squares: the maximum-likelihood pitch
 * of a harmonic tone in white noise. YIN works from
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
 * YIN, and YIN's answer stands. YIN chooses the octave and the neighbourhood;
 * the refined pitch can only cross into the next note when YIN's estimate was
 * within the search span of the quarter-tone, and then it is the closer one.
 *
 * One refiner serves one sample rate and window length. Not thread safe: the
 * analysis thread owns it.
 */
class HarmonicRefiner(private val sampleRate: Int, private val frames: Int) {
    // A Tukey window: flat over the middle so every sample counts equally, as it
    // does in the pitch the window reports, with cosine tapers at the ends to
    // keep one partial's leakage off the next.
    private val taperEdge = TAPER * (frames - 1) / 2.0
    private val taper = DoubleArray(frames) { i ->
        when {
            i < taperEdge -> 0.5 - 0.5 * cos(PI * i / taperEdge)
            i > frames - 1 - taperEdge -> 0.5 - 0.5 * cos(PI * (frames - 1 - i) / taperEdge)
            else -> 1.0
        }
    }

    /** Samples in each taper; the right one mirrors the left. */
    private val taperLength = (0 until frames).count { it < taperEdge }
    private val centre = (frames - 1) / 2.0
    private val windowSum = taper.sum()
    private val signal = DoubleArray(frames)
    private val grid = DoubleArray(GRID.size)
    private val fine = DoubleArray(3)
    private val trial = DoubleArray(GRID.size)
    private val coefficient = DoubleArray(GRID.size * MAX_HARMONICS)
    private val omega = DoubleArray(GRID.size * MAX_HARMONICS)
    private val multiples = DoubleArray(2 * MAX_HARMONICS + 1)
    private val cosineCorrelation = DoubleArray(MAX_HARMONICS + 1)
    private val sineCorrelation = DoubleArray(MAX_HARMONICS)
    private val cosineGram = DoubleArray((MAX_HARMONICS + 1) * (MAX_HARMONICS + 1))
    private val sineGram = DoubleArray(MAX_HARMONICS * MAX_HARMONICS)
    private val forward = DoubleArray(MAX_HARMONICS + 1)

    /** The windowed signal's sum, its correlation with the constant term. */
    private var dc = 0.0
    private val s1 = DoubleArray(GRID.size * MAX_HARMONICS)
    private val s2 = DoubleArray(GRID.size * MAX_HARMONICS)

    /** The refined fundamental near [hz] for the first [frames] samples of [window]; [hz] itself when no better one is found. */
    fun refine(window: FloatArray, hz: Double): Double {
        if (hz <= 0.0 || !hz.isFinite()) return hz
        // The window's DC is fitted with the partials rather than subtracted: a low
        // note's few cycles have a mean of their own, and taking it away leaves
        // a signal no sum of sinusoids fits, which tilts the peak.
        dc = 0.0
        for (i in 0 until frames) {
            signal[i] = window[i] * taper[i]
            dc += signal[i]
        }
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
     * How well a harmonic tone at each of the first [count] frequencies in
     * [f0s] explains the window, into [out]: the energy of the least-squares
     * fit of the first [harmonics] partials, each a real sinusoid, together.
     *
     * Scoring each partial on its own, as a spectrum does, lets the window's
     * lobes of its neighbours and of its own negative-frequency image lean on
     * the score; for a low note, whose partials sit only a few bins apart,
     * that tilts the peak by cents. The joint fit accounts for every overlap
     * through the window's cosine sums ([windowCosine]). Phase is taken at
     * the window's centre, where the window is symmetric, so the cosine and
     * sine parts separate into two small systems.
     *
     * Each partial's correlation comes from a Goertzel recurrence (one
     * multiply and two adds per sample); all of them run side by side in one
     * pass over the window, so the processor overlaps dozens of independent
     * chains instead of waiting on one.
     */
    private fun scores(f0s: DoubleArray, count: Int, harmonics: Int, out: DoubleArray) {
        val chains = count * harmonics
        for (c in 0 until count) {
            for (k in 1..harmonics) {
                val j = c * harmonics + k - 1
                omega[j] = 2.0 * PI * f0s[c] * k / sampleRate
                coefficient[j] = 2.0 * cos(omega[j])
            }
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
            val w = omega[c * harmonics]
            // The window's cosine sums at every multiple of the fundamental the
            // partials' cross terms need: 0 to 2K times it.
            for (d in 0..2 * harmonics) multiples[d] = windowCosine(d * w)
            // The cosine system carries the constant term as its first row: a
            // microphone offset is fitted, never read as a partial.
            val n = harmonics + 1
            cosineCorrelation[0] = dc
            cosineGram[0] = windowSum
            for (k in 0 until harmonics) {
                cosineGram[k + 1] = multiples[k + 1]
                cosineGram[(k + 1) * n] = multiples[k + 1]
            }
            for (k in 0 until harmonics) {
                val j = c * harmonics + k
                // The correlation with e^(-jw(n - m)), phase taken at the window's centre m,
                // where the window is symmetric and the cosine and sine fits separate.
                val wk = omega[j]
                val yRe = s1[j] - cos(wk) * s2[j]
                val yIm = sin(wk) * s2[j]
                val turn = wk * centre
                cosineCorrelation[k + 1] = yRe * cos(turn) + yIm * sin(turn)
                sineCorrelation[k] = yIm * cos(turn) - yRe * sin(turn)
                for (l in 0 until harmonics) {
                    val difference = multiples[kotlin.math.abs(k - l)]
                    val total = multiples[k + l + 2]
                    cosineGram[(k + 1) * n + l + 1] = 0.5 * (difference + total)
                    sineGram[k * harmonics + l] = 0.5 * (difference - total)
                }
            }
            out[c] = explained(cosineGram, cosineCorrelation, n) + explained(sineGram, sineCorrelation, harmonics)
        }
    }

    /**
     * The energy a least-squares fit explains: a' G^-1 a for the [n] by [n]
     * Gram matrix [gram] (row-major, symmetric positive definite) and the
     * correlations [a]. Cholesky in place; the matrix is scratch afterwards.
     */
    private fun explained(gram: DoubleArray, a: DoubleArray, n: Int): Double {
        for (col in 0 until n) {
            var diagonal = gram[col * n + col]
            for (p in 0 until col) diagonal -= gram[col * n + p] * gram[col * n + p]
            if (diagonal <= 0.0) return 0.0
            val root = kotlin.math.sqrt(diagonal)
            gram[col * n + col] = root
            for (row in col + 1 until n) {
                var v = gram[row * n + col]
                for (p in 0 until col) v -= gram[row * n + p] * gram[col * n + p]
                gram[row * n + col] = v / root
            }
        }
        // a' G^-1 a = |L^-1 a|^2.
        var energy = 0.0
        for (row in 0 until n) {
            var v = a[row]
            for (p in 0 until row) v -= gram[row * n + p] * forward[p]
            forward[row] = v / gram[row * n + row]
            energy += forward[row] * forward[row]
        }
        return energy
    }

    /**
     * The sum over the window of taper(n) times cos(nu (n - m)), in closed form:
     * the flat window's sum less both tapers' shortfall from 1, each a sum of
     * cosines with a known geometric total.
     */
    internal fun windowCosine(nu: Double): Double {
        val phase = -nu * centre
        val flat = cosineSum(nu, phase, frames)
        val step = PI / taperEdge
        val shortfall = 0.5 * cosineSum(nu, phase, taperLength) +
            0.25 * (cosineSum(nu + step, phase, taperLength) + cosineSum(nu - step, phase, taperLength))
        return flat - 2.0 * shortfall
    }

    /** The sum of cos(alpha n + phase) for n from 0 to [length] - 1. */
    private fun cosineSum(alpha: Double, phase: Double, length: Int): Double {
        val half = sin(alpha / 2.0)
        if (kotlin.math.abs(half) < 1e-12) return length * cos(phase)
        return sin(length * alpha / 2.0) / half * cos(phase + (length - 1) * alpha / 2.0)
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
