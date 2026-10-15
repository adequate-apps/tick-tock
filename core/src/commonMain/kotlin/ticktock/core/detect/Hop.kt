// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The analysis step (spec 0.3). The hop is fixed in seconds so a reading
 * count means the same playing time at every input rate; the length in
 * frames follows from the rate the device actually delivers.
 */
object Hop {
    /** 2048 frames at 44.1 kHz, about 46.44 ms. */
    const val SECONDS = 2048.0 / 44_100.0

    const val MILLIS = SECONDS * 1000.0

    /** The analysis window is two hops, about 93 ms, above the 78.4 ms floor for A0 (DET-14). */
    const val WINDOW_HOPS = 2

    fun frames(sampleRate: Int): Int {
        require(sampleRate > 0) { "sample rate must be positive, was $sampleRate" }
        return (sampleRate * SECONDS).roundToInt()
    }

    fun windowFrames(sampleRate: Int): Int = frames(sampleRate) * WINDOW_HOPS

    /** Hops in [seconds] of sounding time, rounded to the nearest whole hop. */
    fun count(seconds: Double): Int = (seconds / SECONDS).roundToInt()
}

/**
 * What one window of audio measured, before any policy. A pitch estimator
 * (YIN in production) fills this; the detector classifies it. [hz] that is
 * not a positive finite number means no candidate. [aperiodicity] is the
 * estimator's confidence measure where 0 is a perfect period and 1 is none;
 * [rms] is the unscaled window level, full scale 1.
 */
data class HopEstimate(
    val hz: Double,
    val aperiodicity: Double,
    val rms: Double,
) {
    val hasCandidate: Boolean get() = hz.isFinite() && hz > 0.0

    companion object {
        /** A window with no period in it; [rms] still feeds the level meter. */
        fun none(rms: Double): HopEstimate = HopEstimate(Double.NaN, 1.0, rms)

        /** The level of the first [frames] of [samples], full scale 1. */
        fun rms(samples: FloatArray, frames: Int): Double {
            var sum = 0.0
            for (i in 0 until frames) sum += samples[i].toDouble() * samples[i]
            return sqrt(sum / frames)
        }
    }
}

/**
 * The seam between audio and policy. The production implementation is YIN;
 * tests supply scripted or synthetic estimators. [window] holds [frames]
 * valid samples; the estimator reads and never writes it.
 */
fun interface PitchEstimator {
    fun estimate(window: FloatArray, frames: Int, sampleRate: Int): HopEstimate
}
