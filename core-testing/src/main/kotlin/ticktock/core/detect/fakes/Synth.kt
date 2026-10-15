// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect.fakes

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Test audio for the fake input. A [Sine] keeps its phase across blocks so a
 * tone split into hops is one continuous tone, which is what a microphone
 * delivers; [silence] and [noise] are the other two things a tuner hears.
 */
class Sine(var hz: Double, var amplitude: Double, private val sampleRate: Int) {
    private var phase = 0.0

    fun next(frames: Int): FloatArray {
        val out = FloatArray(frames)
        val step = 2.0 * PI * hz / sampleRate
        for (i in 0 until frames) {
            out[i] = (amplitude * sin(phase)).toFloat()
            phase += step
            if (phase >= 2.0 * PI) phase -= 2.0 * PI
        }
        return out
    }
}

object Synth {
    fun silence(frames: Int): FloatArray = FloatArray(frames)

    /** Uniform white noise at the given RMS, deterministic for [seed]. */
    fun noise(frames: Int, rms: Double, seed: Int = 1): FloatArray {
        val random = Random(seed)
        // Uniform on [-1, 1] has RMS 1/sqrt(3).
        val scale = rms * Math.sqrt(3.0)
        return FloatArray(frames) { (random.nextDouble(-1.0, 1.0) * scale).toFloat() }
    }

    /** RMS of a block, the same figure the estimator reports. */
    fun rms(samples: FloatArray, frames: Int = samples.size): Double {
        var sum = 0.0
        for (i in 0 until frames) sum += samples[i].toDouble() * samples[i]
        return Math.sqrt(sum / frames)
    }
}
