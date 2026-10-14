// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.detect.fakes.Synth
import ticktock.core.music.Pitch
import ticktock.core.yin.TestTones
import ticktock.core.yin.Timbre

/** The seam between YIN and the detector: what one window becomes as a [HopEstimate]. */
class YinEstimatorTest {
    private val rates = listOf(44_100, 48_000)

    @Test
    fun `a sax-like window at A4 = 442 comes back as its frequency, its aperiodicity and its level`() {
        for (rate in rates) {
            val estimator = YinEstimator(ResponseModeTable.of(ResponseMode.NORMAL).aperiodicityCeiling)
            val hz = Pitch.hzFromMidi(64, 442.0)
            val window = TestTones.harmonic(hz, rate, Hop.windowFrames(rate), Timbre.SAX_LIKE.partials)
            val estimate = estimator.estimate(window, window.size, rate)
            assertTrue("$rate: candidate", estimate.hasCandidate)
            val nearest = Pitch.nearest(estimate.hz, 442.0)!!
            assertEquals("$rate: note", 64, nearest.midi)
            assertEquals("$rate: cents", 0.0, nearest.cents, 1.0)
            assertTrue("$rate: aperiodicity ${estimate.aperiodicity}", estimate.aperiodicity < ResponseModeTable.of(ResponseMode.NORMAL).aperiodicityCeiling)
            assertEquals("$rate: rms", Synth.rms(window), estimate.rms, 1e-9)
            assertEquals(rate, estimator.sampleRate)
        }
    }

    @Test
    fun `the threshold is read on every window, so a mode change reaches the estimator`() {
        val detector = Detector(ResponseMode.FAST)
        val seen = ArrayList<Double>()
        val estimator = YinEstimator { detector.params.aperiodicityCeiling.also { seen += it } }
        val window = TestTones.sine(440.0, 48_000, Hop.windowFrames(48_000))
        estimator.estimate(window, window.size, 48_000)
        detector.setMode(ResponseMode.SLOW)
        estimator.estimate(window, window.size, 48_000)
        assertEquals(
            listOf(ResponseModeTable.of(ResponseMode.FAST).aperiodicityCeiling, ResponseModeTable.of(ResponseMode.SLOW).aperiodicityCeiling),
            seen,
        )
    }

    @Test
    fun `DET-15 a window with a NaN or an infinity is no candidate at level zero and is counted`() {
        val estimator = YinEstimator(ResponseModeTable.of(ResponseMode.NORMAL).aperiodicityCeiling)
        val frames = Hop.windowFrames(48_000)
        val clean = TestTones.sine(440.0, 48_000, frames)

        val withNaN = clean.copyOf().also { it[frames / 3] = Float.NaN }
        val poisoned = estimator.estimate(withNaN, frames, 48_000)
        assertFalse(poisoned.hasCandidate)
        assertEquals(0.0, poisoned.rms, 0.0)
        assertEquals(1, estimator.nonFiniteWindows)

        val withInf = clean.copyOf().also { it[7] = Float.NEGATIVE_INFINITY }
        assertFalse(estimator.estimate(withInf, frames, 48_000).hasCandidate)
        assertEquals(2, estimator.nonFiniteWindows)

        // The next clean window reads as if nothing had happened.
        val after = estimator.estimate(clean, frames, 48_000)
        assertEquals(440.0, after.hz, 0.01)
        assertEquals(2, estimator.nonFiniteWindows)
    }

    @Test
    fun `silence is no candidate and white noise is a candidate above every ceiling`() {
        val estimator = YinEstimator(ResponseModeTable.of(ResponseMode.FAST).aperiodicityCeiling)
        val frames = Hop.windowFrames(44_100)
        val silence = estimator.estimate(Synth.silence(frames), frames, 44_100)
        assertFalse(silence.hasCandidate)
        assertEquals(0.0, silence.rms, 0.0)

        val noise = estimator.estimate(Synth.noise(frames, rms = 0.1), frames, 44_100)
        assertTrue("noise aperiodicity ${noise.aperiodicity}", noise.aperiodicity > ResponseModeTable.of(ResponseMode.FAST).aperiodicityCeiling)
        assertEquals(0.1, noise.rms, 0.01)
    }

    @Test
    fun `a stream at a new rate gets a fresh engine and a wrong window length is refused`() {
        val estimator = YinEstimator(ResponseModeTable.of(ResponseMode.NORMAL).aperiodicityCeiling)
        for (rate in rates) {
            val window = TestTones.sine(440.0, rate, Hop.windowFrames(rate))
            assertEquals("$rate", 440.0, estimator.estimate(window, window.size, rate).hz, 0.01)
            assertEquals(rate, estimator.sampleRate)
        }
        val short = FloatArray(Hop.windowFrames(48_000) - 1)
        assertThrows(IllegalArgumentException::class.java) { estimator.estimate(short, short.size, 48_000) }
    }
}
