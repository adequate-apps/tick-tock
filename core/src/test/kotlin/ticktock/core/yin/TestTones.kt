// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.yin

import ticktock.core.music.Pitch
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The three timbres of the DET sweeps (spec 1, PLAN 4.1). */
enum class Timbre(val label: String, val partials: DoubleArray) {
    /** One partial. */
    SINE("sine", doubleArrayOf(1.0)),

    /** A bright reed: ten partials falling off slowly, as a saxophone's spectrum does at mezzo-forte. */
    SAX_LIKE("sax-like", doubleArrayOf(1.0, 0.9, 0.7, 0.6, 0.5, 0.4, 0.3, 0.25, 0.2, 0.15)),

    /**
     * The fundamental 20 dB under the second partial, with the odd partials a
     * low note keeps (DET-08). A tone of only a fundamental and its octave is
     * periodic at the octave to within that 20 dB, and no estimator can call
     * it the lower note from the waveform alone.
     */
    WEAK_FUNDAMENTAL("weak-fundamental", doubleArrayOf(0.1, 1.0, 0.5, 0.3, 0.2)),
}

/** Synthesised windows for the U tier. Every tone is deterministic for its arguments. */
object TestTones {
    /** A pure sine at [hz] with the given starting phase, peak 0.5. */
    fun sine(hz: Double, sampleRate: Int, frames: Int, phase: Double = 0.0): FloatArray =
        harmonic(hz, sampleRate, frames, doubleArrayOf(1.0), phases = doubleArrayOf(phase))

    /**
     * A sum of harmonics of [hz] with the given relative [amplitudes]. Partials
     * at or above the Nyquist frequency are left out, so the tone is the same
     * band-limited signal a microphone would deliver. Phases come from [seed]
     * unless given, so a timbre is a fixed waveform rather than a cosine stack
     * with a spike at the origin. The peak is normalised to 0.5.
     */
    fun harmonic(
        hz: Double,
        sampleRate: Int,
        frames: Int,
        amplitudes: DoubleArray,
        seed: Long = 7L,
        phases: DoubleArray? = null,
    ): FloatArray {
        val random = Random(seed)
        val phase = phases ?: DoubleArray(amplitudes.size) { random.nextDouble() * 2.0 * PI }
        val nyquist = sampleRate / 2.0
        val samples = DoubleArray(frames)
        for ((index, amplitude) in amplitudes.withIndex()) {
            val partialHz = hz * (index + 1)
            if (partialHz >= nyquist) continue
            val step = 2.0 * PI * partialHz / sampleRate
            for (i in 0 until frames) samples[i] += amplitude * sin(step * i + phase[index])
        }
        var peak = 0.0
        for (s in samples) peak = maxOf(peak, abs(s))
        val gain = if (peak > 0.0) 0.5 / peak else 1.0
        return FloatArray(frames) { (samples[it] * gain).toFloat() }
    }

    /** One analysis window of [timbre] at [midi] against [a4Hz], sized for [sampleRate]. */
    fun window(timbre: Timbre, midi: Int, a4Hz: Double, sampleRate: Int): FloatArray =
        harmonic(Pitch.hzFromMidi(midi, a4Hz), sampleRate, Yin.windowFrames(sampleRate), timbre.partials, seed = 1000L + midi)

    /** White noise, peak about [peak], deterministic for [seed]. */
    fun noise(frames: Int, peak: Double = 0.1, seed: Long = 3L): FloatArray {
        val random = Random(seed)
        return FloatArray(frames) { ((random.nextDouble() * 2.0 - 1.0) * peak).toFloat() }
    }
}
