// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.yin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.detect.Hop
import ticktock.core.music.Pitch
import kotlin.math.abs
import kotlin.math.ln

/** The refinement step on its own: what it fixes, and what it leaves to YIN. */
class HarmonicRefinerTest {
    private val rates = listOf(44_100, 48_000)

    private fun cents(read: Double, truth: Double) = 1200.0 * ln(read / truth) / ln(2.0)

    @Test
    fun `a breathy tone 20 dB over white noise refines to within a fifth of a cent where YIN alone wanders`() {
        for (rate in rates) {
            val frames = Hop.windowFrames(rate)
            val yin = Yin(rate)
            val refiner = HarmonicRefiner(rate, frames)
            var worstYin = 0.0
            var worstRefined = 0.0
            for ((i, midi) in listOf(60, 64, 67, 72, 79).withIndex()) {
                val hz = Pitch.hzFromMidi(midi + 0.07 * i, 440.0)
                val tone = TestTones.harmonic(hz, rate, frames, doubleArrayOf(1.0, 0.4, 0.15, 0.08), seed = 11L + midi)
                val window = TestTones.withNoise(tone, snrDb = 20.0, seed = 31L + midi)
                val found = (yin.analyse(window, 0.3) as YinResult.Period).hz
                worstYin = maxOf(worstYin, abs(cents(found, hz)))
                worstRefined = maxOf(worstRefined, abs(cents(refiner.refine(window, found), hz)))
            }
            assertTrue("$rate Hz: refined worst %.3f c".format(worstRefined), worstRefined <= 0.2)
            assertTrue("$rate Hz: the refinement must beat YIN here (YIN %.3f c, refined %.3f c)".format(worstYin, worstRefined), worstRefined < worstYin)
        }
    }

    @Test
    fun `a weak fundamental is refined on its upper partials, down to A0`() {
        for (rate in rates) {
            val frames = Hop.windowFrames(rate)
            val refiner = HarmonicRefiner(rate, frames)
            for (midi in 21..45) {
                val hz = Pitch.hzFromMidi(midi + 0.3, 440.0)
                val window = TestTones.harmonic(hz, rate, frames, Timbre.WEAK_FUNDAMENTAL.partials)
                // Started 2 c off: the fit of every partial and the window's DC together lands on the tone.
                assertEquals("$rate Hz MIDI $midi", 0.0, cents(refiner.refine(window, hz * Math.pow(2.0, 2.0 / 1200.0)), hz), 0.01)
            }
        }
    }

    @Test
    fun `a microphone offset does not move a low note`() {
        val rate = 48_000
        val frames = Hop.windowFrames(rate)
        val hz = Pitch.hzFromMidi(24, 440.0)
        val tone = TestTones.harmonic(hz, rate, frames, Timbre.SAX_LIKE.partials)
        val offset = FloatArray(frames) { tone[it] + 0.05f }
        assertEquals(0.0, cents(HarmonicRefiner(rate, frames).refine(offset, hz * 1.001), hz), 0.01)
    }

    @Test
    fun `a start too far from the tone leaves YIN's answer alone, and so does a start that is no frequency`() {
        val rate = 48_000
        val frames = Hop.windowFrames(rate)
        val hz = 440.0
        val window = TestTones.harmonic(hz, rate, frames, Timbre.SAX_LIKE.partials)
        val refiner = HarmonicRefiner(rate, frames)
        val farOff = hz * Math.pow(2.0, 20.0 / 1200.0)
        assertEquals(farOff, refiner.refine(window, farOff), 0.0)
        assertEquals(0.0, refiner.refine(window, 0.0), 0.0)
        assertTrue(refiner.refine(window, Double.NaN).isNaN())
    }

    @Test
    fun `the refinement never moves a reading more than its search span`() {
        for (rate in rates) {
            val frames = Hop.windowFrames(rate)
            val refiner = HarmonicRefiner(rate, frames)
            for (seed in 1L..40L) {
                val window = TestTones.noise(frames, peak = 0.3, seed = seed)
                val start = 110.0 * seed
                assertTrue(abs(cents(refiner.refine(window, start), start)) < HarmonicRefiner.SEARCH_SPAN)
            }
        }
    }
}
