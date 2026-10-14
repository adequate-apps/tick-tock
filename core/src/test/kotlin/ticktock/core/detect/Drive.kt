// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import ticktock.core.music.Pitch
import kotlin.random.Random

/**
 * Feeds scripted estimates to a [Detector] hop by hop and keeps every
 * decision, so a policy test reads like the scenario it checks. Levels are
 * on the UNPROCESSED scale: [TONE_RMS] is a comfortable mezzo-forte, far
 * above every floor.
 */
internal class Drive(val detector: Detector) {
    var hop = 0L
        private set
    val decisions = mutableListOf<Decision>()

    val saved: List<Reading> get() = decisions.mapNotNull { it.saved }
    val attacks: List<Attack> get() = decisions.mapNotNull { it.attack }
    val releases: List<Release> get() = decisions.mapNotNull { it.release }

    fun feed(estimate: HopEstimate, hops: Int, gain: Double = 1.0): List<Decision> {
        val out = ArrayList<Decision>(hops)
        repeat(hops) {
            val decision = detector.step(estimate, hop++, gain)
            decisions += decision
            out += decision
        }
        return out
    }

    fun tone(hz: Double, hops: Int, aperiodicity: Double = TONE_APERIODICITY, rms: Double = TONE_RMS, gain: Double = 1.0): List<Decision> =
        feed(HopEstimate(hz, aperiodicity, rms), hops, gain)

    fun note(midi: Int, hops: Int, a4Hz: Double = Pitch.A4_DEFAULT_HZ): List<Decision> = tone(Pitch.hzFromMidi(midi, a4Hz), hops)

    fun silence(hops: Int): List<Decision> = feed(HopEstimate.none(0.0), hops)

    /** Breath or white noise: a candidate every hop, none of them periodic. */
    fun noise(hops: Int, rms: Double = TONE_RMS, seed: Int = 7): List<Decision> {
        val random = Random(seed)
        val out = ArrayList<Decision>(hops)
        repeat(hops) {
            val estimate = HopEstimate(random.nextDouble(80.0, 2000.0), random.nextDouble(0.3, 0.9), rms)
            out += feed(estimate, 1).single()
        }
        return out
    }

    companion object {
        const val TONE_RMS = 0.1
        const val TONE_APERIODICITY = 0.02
    }
}
