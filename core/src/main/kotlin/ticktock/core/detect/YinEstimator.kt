// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import ticktock.core.yin.Yin
import ticktock.core.yin.YinResult
import kotlin.math.sqrt

/**
 * The production [PitchEstimator]: YIN behind the detector's seam.
 *
 * The threshold YIN searches with is read through [threshold] on every
 * window. Wired to a [Detector] it is the mode's aperiodicity ceiling, so a
 * mode change moves the "first dip that counts" rule together with the gate
 * (POL-10): a Fast-mode player is not held to Slow's threshold, and a Slow
 * player's estimator does not accept a dip the gate would then refuse. A
 * window whose deepest dip misses the threshold still yields that dip as the
 * candidate, and the detector's ceiling rejects it; the level meter sees the
 * window's RMS either way.
 *
 * A window holding a NaN or an infinity carries no level and no period: it
 * comes back as no candidate at level 0 and is counted in
 * [nonFiniteWindows], and the next clean window is analysed normally
 * (DET-15).
 *
 * One [Yin] serves one sample rate; a stream at a new rate gets a new one on
 * its first window. Not thread safe: the analysis thread owns it.
 */
class YinEstimator(private val threshold: () -> Double) : PitchEstimator {
    /** YIN searching with the detector's current ceiling, so the two move together. */
    constructor(detector: Detector) : this({ detector.params.aperiodicityCeiling })

    /** YIN at one fixed threshold, for a test or a tool that has no detector. */
    constructor(threshold: Double) : this({ threshold })

    private var yin: Yin? = null
    private var scratch = FloatArray(0)

    /** Windows skipped because they held a NaN or an infinity, since this estimator was made. */
    var nonFiniteWindows: Long = 0
        private set

    /** The sample rate of the last window, 0 before the first. */
    var sampleRate: Int = 0
        private set

    override fun estimate(window: FloatArray, frames: Int, sampleRate: Int): HopEstimate {
        val engine = engineFor(sampleRate)
        require(frames == engine.windowFrames) {
            "a window of $frames frames at $sampleRate Hz; the analysis window is ${engine.windowFrames}"
        }
        // The analyser hands over a whole window; a longer buffer with a shorter
        // valid prefix is copied so YIN only ever sees valid samples.
        val view = if (window.size == frames) window else window.copyInto(scratch, 0, 0, frames)
        return when (val result = engine.analyse(view, threshold())) {
            is YinResult.Period -> HopEstimate(result.hz, result.aperiodicity, rms(window, frames))
            YinResult.NoPeriod -> HopEstimate.none(rms(window, frames))
            YinResult.NonFinite -> {
                nonFiniteWindows++
                HopEstimate.none(0.0)
            }
        }
    }

    private fun engineFor(rate: Int): Yin {
        val current = yin
        if (current != null && sampleRate == rate) return current
        val fresh = Yin(rate)
        yin = fresh
        scratch = FloatArray(fresh.windowFrames)
        sampleRate = rate
        return fresh
    }

    private fun rms(samples: FloatArray, frames: Int): Double {
        var sum = 0.0
        for (i in 0 until frames) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / frames)
    }
}
