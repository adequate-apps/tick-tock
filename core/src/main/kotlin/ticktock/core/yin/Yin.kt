// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.yin

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What one analysis window yielded. */
sealed interface YinResult {
    /**
     * A period was found. [aperiodicity] is the normalised difference at the
     * period, 0 for a perfectly periodic window; [meetsThreshold] is false when
     * no dip reached the threshold and this is the deepest dip instead, which
     * the detector accepts or rejects against its mode's ceiling (POL-10).
     */
    data class Period(
        val hz: Double,
        val periodFrames: Double,
        val aperiodicity: Double,
        val meetsThreshold: Boolean,
    ) : YinResult

    /** No dip inside the search range, or the first dip that counts lies outside it. */
    data object NoPeriod : YinResult

    /** The window held NaN or an infinity; it was skipped and counted (DET-15). */
    data object NonFinite : YinResult
}

/**
 * Period estimation after de Cheveigné and Kawahara (2002): the difference
 * function, its cumulative-mean normalisation, an absolute threshold, and
 * parabolic refinement, with the four changes the specification adds:
 *
 * - the lag search covers [LOW_HZ] to [HIGH_HZ] (DET-01);
 * - the parabola is fitted to the difference function itself, never to the
 *   normalised one, and only where its three values are positive (DET-02);
 * - a short period is re-measured on the dip near a multiple of itself and
 *   divided back, because the lag grid resolves a long dip far better (DET-03);
 * - a dip whose sampled values all sit above the threshold still counts when
 *   its interpolated minimum is below it (DET-04).
 *
 * On top of those, the period that counts is polished on the band-limited
 * difference function, which [refinePeriod] explains; the same evaluation
 * gives the interpolated minimum of DET-04.
 *
 * The autocorrelation comes from an FFT of the smallest 5-smooth length that
 * holds the window plus the longest lag (DET-05). The window is fixed in
 * milliseconds, so the same note gives the same reading at every rate; the
 * hop is the spec's 2048 frames at 44.1 kHz (spec 0.3).
 *
 * One instance serves one sample rate, owns its scratch space and allocates
 * nothing per window. It is not thread safe.
 */
class Yin(val sampleRate: Int) {
    val windowFrames: Int = windowFrames(sampleRate)
    val hopFrames: Int = hopFrames(sampleRate)

    /** Shortest lag searched: the period of [HIGH_HZ], rounded down so that frequency is inside. */
    val minLag: Int = floor(sampleRate / HIGH_HZ).toInt()

    /** Longest lag searched: the period of [LOW_HZ], rounded up so that frequency is inside. */
    val maxLag: Int = ceil(sampleRate / LOW_HZ).toInt()

    /**
     * Lags 0..[maxLag] + 1 are computed: the extra one is the right-hand
     * neighbour a dip at [maxLag] needs to count as a local minimum.
     */
    val fftLength: Int = Fft.smallestFiveSmoothAtLeast(windowFrames + maxLag + 1)

    /** Windows skipped because they held a NaN or an infinity (DET-15). */
    var nonFiniteWindows: Int = 0
        private set

    private val fft = Fft(fftLength)
    private val re = DoubleArray(fftLength)
    private val im = DoubleArray(fftLength)
    private val power = DoubleArray(fftLength)
    private val prefix = DoubleArray(windowFrames + 1)
    private val lagCount = maxLag + 2
    private val diff = DoubleArray(lagCount)
    private val cmnd = DoubleArray(lagCount)
    private val runningMean = DoubleArray(lagCount)

    init {
        require(sampleRate > 0) { "sample rate $sampleRate" }
        require(minLag >= 2) { "sample rate $sampleRate cannot resolve $HIGH_HZ Hz" }
        require(windowFrames > maxLag + 1) { "window of $windowFrames frames is shorter than the longest lag" }
    }

    /**
     * Analyses one window of [windowFrames] samples. A dip counts as the period
     * when its normalised difference is below [threshold], sampled or
     * interpolated; the first such dip wins, which is what keeps a periodic
     * signal from reading an octave low.
     */
    fun analyse(window: FloatArray, threshold: Double): YinResult {
        require(window.size == windowFrames) { "window of ${window.size} frames, expected $windowFrames at $sampleRate Hz" }
        for (sample in window) {
            if (!sample.isFinite()) {
                nonFiniteWindows++
                return YinResult.NonFinite
            }
        }
        computeDifference(window)

        var bestLag = -1
        var bestDepth = Double.MAX_VALUE
        for (lag in minLag..maxLag) {
            if (!(cmnd[lag - 1] > cmnd[lag] && cmnd[lag] <= cmnd[lag + 1])) continue
            val sampled = cmnd[lag]
            val depth = if (lag < REFINEMENT_LAG) {
                // Here one lag is a sizeable fraction of the period, and the
                // sampled dip can sit far above the true minimum between the
                // samples; a parabola through three such points overstates the
                // depth as well. The band-limited value at the estimated period
                // is the honest one.
                min(sampled, interpolatedCmnd(estimatePeriod(lag)))
            } else {
                min(sampled, parabolicMinimum(cmnd, lag))
            }
            if (depth < threshold) return resultFor(refinePeriod(lag), depth, true)
            if (depth < bestDepth) {
                bestDepth = depth
                bestLag = lag
            }
        }
        if (bestLag < 0) return YinResult.NoPeriod
        return resultFor(refinePeriod(bestLag), bestDepth, false)
    }

    private fun resultFor(periodFrames: Double, aperiodicity: Double, meetsThreshold: Boolean): YinResult {
        val hz = sampleRate / periodFrames
        if (hz < LOW_HZ || hz > HIGH_HZ) return YinResult.NoPeriod
        return YinResult.Period(hz, periodFrames, max(0.0, aperiodicity), meetsThreshold)
    }

    /**
     * Fills [diff] with the mean-square difference per lag and [cmnd] with its
     * cumulative-mean normalisation. Dividing each lag's sum by the number of
     * terms that formed it keeps the function level across lags, which the
     * cumulative mean assumes; the sums themselves come from prefix sums of
     * the squared samples and the autocorrelation.
     */
    private fun computeDifference(window: FloatArray) {
        val n = windowFrames
        prefix[0] = 0.0
        for (i in 0 until n) {
            val x = window[i].toDouble()
            re[i] = x
            im[i] = 0.0
            prefix[i + 1] = prefix[i] + x * x
        }
        for (i in n until fftLength) {
            re[i] = 0.0
            im[i] = 0.0
        }
        fft.forward(re, im)
        for (k in 0 until fftLength) {
            power[k] = re[k] * re[k] + im[k] * im[k]
            re[k] = power[k]
            im[k] = 0.0
        }
        // The spectrum of a real signal is even, so transforming the power
        // spectrum forward gives the autocorrelation scaled by the length.
        fft.forward(re, im)
        val scale = 1.0 / fftLength

        diff[0] = 0.0
        cmnd[0] = 1.0
        runningMean[0] = 0.0
        var running = 0.0
        for (lag in 1 until lagCount) {
            val head = prefix[n - lag]
            val tail = prefix[n] - prefix[lag]
            diff[lag] = (head + tail - 2.0 * re[lag] * scale) / (n - lag)
            running += diff[lag]
            runningMean[lag] = running / lag
            cmnd[lag] = if (running > 0.0) diff[lag] / runningMean[lag] else 1.0
        }
    }

    /**
     * The period in frames for a dip of the normalised function at [dipLag]
     * before the polish: the parabola on the raw difference function (DET-02)
     * and the multiple refinement for short lags (DET-03). Good to a few
     * hundredths of a lag, which is all the depth check needs.
     */
    private fun estimatePeriod(dipLag: Int): Double {
        val lag = descendToRawMinimum(dipLag)
        val first = lag + parabolicOffset(diff, lag)
        val k = refinementMultiple(first)
        val nearMultiple = (k * first).roundToInt()
        if (k == 1 || nearMultiple > maxLag) return first
        val multiple = descendToRawMinimum(nearMultiple)
        val refined = (multiple + parabolicOffset(diff, multiple)) / k
        // The multiple sharpens the first estimate; a dip that lands anywhere
        // else is some other feature of the signal, and the first estimate stands.
        return if (abs(refined - first) <= 0.5) refined else first
    }

    /**
     * The period for the dip that counts: [estimatePeriod], then the minimum
     * of the band-limited difference function near it, measured on the
     * multiple when there is one and divided back. The polish is what a
     * bright timbre needs at the top of the range: with a sixth partial
     * advancing three radians per lag, the dip is nothing like a parabola
     * within one lag, and the fit alone reads one to two cents off and
     * differently at each rate. The band-limited function has its minimum at
     * the period whatever the partials are.
     */
    private fun refinePeriod(dipLag: Int): Double {
        val estimate = estimatePeriod(dipLag)
        val k = refinementMultiple(estimate)
        val nearMultiple = (k * estimate).roundToInt()
        if (k == 1 || nearMultiple > maxLag) return polishMinimum(estimate)
        return polishMinimum(k * estimate) / k
    }

    /**
     * The minimum of the band-limited difference function within half a lag
     * of [estimate]: a parabola through three evaluations, re-centred on its
     * vertex with the step halved each round. The step starts small enough
     * that the highest partial advances well under a radian across it, so
     * each round shrinks the error by a large factor.
     */
    private fun polishMinimum(estimate: Double): Double {
        val low = max(1.0, estimate - 0.5)
        val high = min(maxLag.toDouble(), estimate + 0.5)
        var t = estimate.coerceIn(low, high)
        var h = POLISH_FIRST_STEP
        repeat(POLISH_ROUNDS) {
            val a = differenceAt(t - h)
            val b = differenceAt(t)
            val c = differenceAt(t + h)
            val curvature = a - 2.0 * b + c
            if (curvature > 0.0) {
                val step = ((a - c) / (2.0 * curvature) * h).coerceIn(-h, h)
                t = (t + step).coerceIn(low, high)
            }
            h /= 2.0
        }
        return t
    }

    /**
     * The nearest local minimum of the raw difference function, starting from a
     * dip of the normalised one. The normalisation tilts the function, so the
     * two minima can sit a lag apart; the walk is bounded because a dip that
     * keeps descending further is a different dip.
     */
    private fun descendToRawMinimum(start: Int): Int {
        var lag = start.coerceIn(1, maxLag)
        var steps = 0
        while (steps < MAX_DESCENT && lag > 1 && diff[lag - 1] < diff[lag]) {
            lag--
            steps++
        }
        while (steps < MAX_DESCENT && lag < maxLag && diff[lag + 1] < diff[lag]) {
            lag++
            steps++
        }
        return lag
    }

    /**
     * The mean-square difference at a fractional lag [t]: the autocorrelation
     * evaluated from the power spectrum, which is its band-limited
     * interpolation, and the prefix sums interpolated linearly, because they
     * vary slowly against the thousands of terms that form them.
     */
    private fun differenceAt(t: Double): Double {
        val lag = t.coerceIn(1.0, maxLag.toDouble())
        val n = windowFrames
        val head = linear(prefix, n - lag)
        val tail = prefix[n] - linear(prefix, lag)
        return (head + tail - 2.0 * autocorrelationAt(lag)) / (n - lag)
    }

    /** The normalised difference at a fractional lag [t], with the cumulative mean interpolated linearly. */
    private fun interpolatedCmnd(t: Double): Double {
        val lag = t.coerceIn(1.0, maxLag.toDouble())
        val mean = linear(runningMean, lag)
        return if (mean > 0.0) max(0.0, differenceAt(lag) / mean) else 1.0
    }

    /** The autocorrelation at a fractional lag: a cosine sum over the half spectrum by phasor rotation. */
    private fun autocorrelationAt(lag: Double): Double {
        val theta = 2.0 * PI * lag / fftLength
        val rotateCos = cos(theta)
        val rotateSin = sin(theta)
        var c = 1.0
        var s = 0.0
        var sum = power[0]
        val half = fftLength / 2
        val upper = (fftLength + 1) / 2
        for (k in 1 until upper) {
            val nextC = c * rotateCos - s * rotateSin
            s = s * rotateCos + c * rotateSin
            c = nextC
            sum += 2.0 * power[k] * c
        }
        if (fftLength % 2 == 0) {
            val nextC = c * rotateCos - s * rotateSin
            sum += power[half] * nextC
        }
        return sum / fftLength
    }

    private fun linear(values: DoubleArray, at: Double): Double {
        val lower = floor(at).toInt().coerceIn(0, values.size - 1)
        val upper = min(lower + 1, values.size - 1)
        val fraction = at - lower
        return values[lower] + (values[upper] - values[lower]) * fraction
    }

    companion object {
        /** The analysis step, held in seconds so it is the same length at every rate. */
        const val HOP_SECONDS = 2048.0 / 44100.0

        /** Two hops: above two periods of [LOW_HZ] with room for the interpolation (DET-14). */
        const val WINDOW_SECONDS = 2.0 * HOP_SECONDS

        /** Lowest frequency searched: below A0 at A4 = 430 Hz (DET-01). */
        const val LOW_HZ = 25.5

        /** Highest frequency searched: above C8 at A4 = 450 Hz plus a quarter tone (DET-01). */
        const val HIGH_HZ = 4500.0

        /**
         * A period shorter than this many lags is re-measured on the dip near
         * the multiple that brings it closest to this length (DET-03). At this
         * length the grid samples a period finely enough that a parabola's bias
         * is well under a hundredth of a cent, while at ten lags it reaches cents.
         */
        const val REFINEMENT_LAG = 128

        /** How far the raw minimum may sit from the normalised one before it counts as another dip. */
        private const val MAX_DESCENT = 2

        /** First step of the polish, in lags: a quarter lag keeps even a partial near Nyquist under a radian. */
        private const val POLISH_FIRST_STEP = 0.25

        /** Rounds of the polish; four take the position error below a millionth of a lag. */
        private const val POLISH_ROUNDS = 4

        fun hopFrames(sampleRate: Int): Int = Math.round(sampleRate * HOP_SECONDS).toInt()

        fun windowFrames(sampleRate: Int): Int = Math.round(sampleRate * WINDOW_SECONDS).toInt()

        /** k = round(REFINEMENT_LAG / lag) for short lags, 1 from [REFINEMENT_LAG] up (DET-03). */
        fun refinementMultiple(lagFrames: Double): Int =
            if (lagFrames >= REFINEMENT_LAG) 1 else max(1, (REFINEMENT_LAG / lagFrames).roundToInt())

        /**
         * Offset of the vertex of the parabola through (-1, [a]), (0, [b]),
         * (1, [c]) from the middle point, or 0 when any value is not positive
         * or the three do not form a minimum (DET-02). A value at or below
         * zero means the signal repeats exactly at that lag, and the
         * interpolation has nothing to add there.
         */
        internal fun parabolicOffset(a: Double, b: Double, c: Double): Double {
            if (a <= 0.0 || b <= 0.0 || c <= 0.0) return 0.0
            val curvature = a - 2.0 * b + c
            if (curvature <= 0.0) return 0.0
            return ((a - c) / (2.0 * curvature)).coerceIn(-1.0, 1.0)
        }

        private fun parabolicOffset(values: DoubleArray, lag: Int): Double =
            if (lag < 1 || lag + 1 >= values.size) 0.0 else parabolicOffset(values[lag - 1], values[lag], values[lag + 1])

        /** The value at the vertex of the parabola through the three values around [lag], at least 0 and at most the middle value. */
        internal fun parabolicMinimum(values: DoubleArray, lag: Int): Double {
            val b = values[lag]
            if (lag < 1 || lag + 1 >= values.size) return b
            val a = values[lag - 1]
            val c = values[lag + 1]
            val curvature = a - 2.0 * b + c
            if (curvature <= 0.0) return b
            val vertex = b - (a - c) * (a - c) / (8.0 * curvature)
            return vertex.coerceIn(0.0, b)
        }
    }
}
