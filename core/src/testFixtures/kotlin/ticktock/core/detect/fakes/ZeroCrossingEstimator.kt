// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect.fakes

import ticktock.core.detect.HopEstimate
import ticktock.core.detect.PitchEstimator
import kotlin.math.sqrt

/**
 * A [PitchEstimator] for synthetic test audio, so the detector and the input
 * path can be exercised on frames before the real estimator is wired in. It
 * measures the spacing of rising zero crossings with linear interpolation,
 * which is exact enough on a clean sine for cents assertions and useless on
 * anything else, as a test fixture should be. Aperiodicity is the spread of
 * the crossing intervals relative to their mean: near zero for a tone, large
 * for noise.
 */
class ZeroCrossingEstimator : PitchEstimator {
    override fun estimate(window: FloatArray, frames: Int, sampleRate: Int): HopEstimate {
        val rms = Synth.rms(window, frames)
        val crossings = ArrayList<Double>()
        for (i in 1 until frames) {
            val before = window[i - 1]
            val after = window[i]
            if (before < 0f && after >= 0f) {
                // Fraction of a sample from i-1 to the crossing.
                val fraction = before / (before - after)
                crossings += (i - 1) + fraction.toDouble()
            }
        }
        if (crossings.size < MIN_CROSSINGS) return HopEstimate.none(rms)
        val periods = DoubleArray(crossings.size - 1) { crossings[it + 1] - crossings[it] }
        val mean = periods.average()
        val variance = periods.sumOf { (it - mean) * (it - mean) } / periods.size
        val aperiodicity = sqrt(variance) / mean
        val hz = sampleRate / ((crossings.last() - crossings.first()) / periods.size)
        return HopEstimate(hz, aperiodicity, rms)
    }

    companion object {
        const val MIN_CROSSINGS = 3
    }
}
