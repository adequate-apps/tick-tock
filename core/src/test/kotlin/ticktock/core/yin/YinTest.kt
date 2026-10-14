// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.yin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.music.NearestNote
import ticktock.core.music.Pitch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Normal mode's aperiodicity ceiling (spec 2.1), the threshold every DET test analyses with. */
const val NORMAL_THRESHOLD = 0.10

val RATES = intArrayOf(44100, 48000)

/** A typed failure, so a test can use it where a value is expected. */
fun failWith(message: String): Nothing = throw AssertionError(message)

/** The reading the detector would form from a window, or a failure naming the result. */
fun Yin.reading(window: FloatArray, a4Hz: Double = 440.0, threshold: Double = NORMAL_THRESHOLD): NearestNote {
    val result = analyse(window, threshold)
    val period = result as? YinResult.Period ?: failWith("expected a period, got $result")
    assertTrue("the period must meet the threshold, aperiodicity ${period.aperiodicity}", period.meetsThreshold)
    return Pitch.nearest(period.hz, a4Hz) ?: failWith("no note for ${period.hz} Hz")
}

fun Yin.hz(window: FloatArray, threshold: Double = NORMAL_THRESHOLD): Double {
    val result = analyse(window, threshold)
    return (result as? YinResult.Period)?.hz ?: failWith("expected a period, got $result")
}

fun assertNote(label: String, expectedMidi: Int, toleranceCents: Double, note: NearestNote) {
    assertEquals("$label: note (read ${note.midi} at ${"%.2f".format(note.cents)} c)", expectedMidi, note.midi)
    assertTrue("$label: ${"%.3f".format(note.cents)} c is outside ±$toleranceCents c", abs(note.cents) <= toleranceCents)
}

class YinTest {
    /**
     * DET-01 with the sweep ends of decision 16: the band clears A0 at
     * A4 = 415 by the half semitone the nearest-note window needs below, and
     * C8 at A4 = 466 by the same above, so a player inside the window at
     * either end of the A4 range still gets a reading.
     */
    @Test
    fun `DET-01 the lag search covers 25 Hz to 4570 Hz at both rates, half a semitone past A0 at 415 and C8 at 466`() {
        assertEquals(25.0, Yin.LOW_HZ, 0.0)
        assertEquals(4570.0, Yin.HIGH_HZ, 0.0)
        val halfSemitone = Math.pow(2.0, 50.0 / 1200.0)
        val lowestA0 = Pitch.hzFromMidi(21, Pitch.A4_HZ.start)
        val highestC8 = Pitch.hzFromMidi(108, Pitch.A4_HZ.endInclusive)
        assertTrue("A0 at ${Pitch.A4_HZ.start} is $lowestA0 Hz; the floor must sit half a semitone under it", Yin.LOW_HZ <= lowestA0 / halfSemitone)
        assertTrue("C8 at ${Pitch.A4_HZ.endInclusive} is $highestC8 Hz; the ceiling must sit half a semitone over it", Yin.HIGH_HZ >= highestC8 * halfSemitone)
        for (rate in RATES) {
            val yin = Yin(rate)
            assertEquals("minLag at $rate", floor(rate / Yin.HIGH_HZ).toInt(), yin.minLag)
            assertEquals("maxLag at $rate", ceil(rate / Yin.LOW_HZ).toInt(), yin.maxLag)
            assertTrue("the longest lag is below the floor at $rate", rate.toDouble() / yin.maxLag <= Yin.LOW_HZ)
            assertTrue("the shortest lag is above the ceiling at $rate", rate.toDouble() / yin.minLag >= Yin.HIGH_HZ)

            val frames = yin.windowFrames
            assertEquals("25.5 Hz at $rate", 25.5, yin.hz(TestTones.sine(25.5, rate, frames)), 25.5 * 0.6 / 1731.0)
            assertEquals("4500 Hz at $rate", 4500.0, yin.hz(TestTones.sine(4500.0, rate, frames)), 4500.0 * 0.6 / 1731.0)
            assertEquals("24.5 Hz is below the floor at $rate", YinResult.NoPeriod, yin.analyse(TestTones.sine(24.5, rate, frames), NORMAL_THRESHOLD))
        }
    }

    @Test
    fun `DET-02 the parabola needs three positive values and sits on the raw difference`() {
        // A minimum between the samples: the offset is the vertex of the parabola
        // through the three values, here y = (x - 0.25)^2 sampled at -1, 0, 1.
        assertEquals(0.0, Yin.parabolicOffset(1.0, 0.5, 1.0), 1e-12)
        assertEquals(0.25, Yin.parabolicOffset(1.5625, 0.0625, 0.5625), 1e-12)
        assertEquals(-0.25, Yin.parabolicOffset(0.5625, 0.0625, 1.5625), 1e-12)
        assertEquals(1.0 / 6.0, Yin.parabolicOffset(1.5, 0.5, 1.0), 1e-12)
        // A zero or negative value means the signal repeats there exactly; no fit.
        assertEquals(0.0, Yin.parabolicOffset(1.0, 0.0, 1.0), 0.0)
        assertEquals(0.0, Yin.parabolicOffset(0.0, 0.5, 1.0), 0.0)
        assertEquals(0.0, Yin.parabolicOffset(1.0, 0.5, -1e-12), 0.0)
        // Three points that do not form a minimum give no offset either.
        assertEquals(0.0, Yin.parabolicOffset(0.5, 1.0, 1.5), 0.0)

        // A period of 100.5 lags lands exactly between two samples. Without the
        // interpolation the reading is 8.6 c off; a fit on the normalised
        // function is tilted by its own denominator and reads about 0.1 c off.
        for (rate in RATES) {
            val yin = Yin(rate)
            val hz = rate / 100.5
            val note = Pitch.nearest(yin.hz(TestTones.sine(hz, rate, yin.windowFrames, phase = 0.9)), 440.0)!!
            val expected = Pitch.nearest(hz, 440.0)!!
            assertEquals(expected.midi, note.midi)
            assertEquals("period 100.5 at $rate", expected.cents, note.cents, 0.02)
        }
    }

    @Test
    fun `DET-03 k = round(128 over L) below 128 lags and 1 from there up`() {
        assertEquals(12, Yin.refinementMultiple(10.53))
        assertEquals(13, Yin.refinementMultiple(9.59))
        assertEquals(3, Yin.refinementMultiple(42.6))
        assertEquals(2, Yin.refinementMultiple(64.1))
        assertEquals(2, Yin.refinementMultiple(85.3))
        assertEquals(1, Yin.refinementMultiple(100.0))
        assertEquals(1, Yin.refinementMultiple(127.9))
        assertEquals(1, Yin.refinementMultiple(128.0))
        assertEquals(1, Yin.refinementMultiple(1800.0))
        assertEquals(128, Yin.REFINEMENT_LAG)
    }

    @Test
    fun `DET-03 C8 at 44,1 kHz reads within 0,6 c`() {
        val yin = Yin(44100)
        val note = yin.reading(TestTones.window(Timbre.SINE, 108, 440.0, 44100))
        assertNote("C8 sine at 44.1 kHz", 108, 0.6, note)
    }

    /**
     * DET-04: the sax-like C8 at 44.1 kHz has its dip at 10.53 lags. Every
     * sampled value of the normalised difference around that dip is above the
     * threshold, as the independent sum below shows, and only the interpolated
     * minimum reaches it. A first-lag-below-threshold rule reads this tone an
     * octave or more low.
     */
    @Test
    fun `DET-04 a dip sampled above the threshold counts when its interpolated minimum is below it`() {
        val rate = 44100
        val window = TestTones.window(Timbre.SAX_LIKE, 108, 440.0, rate)
        val sampled = normalisedDifference(window, maxLag = 14)
        for (lag in 9..12) {
            assertTrue("sampled d'($lag) = ${"%.3f".format(sampled[lag])} must sit above the threshold for this test to mean anything", sampled[lag] >= NORMAL_THRESHOLD)
        }
        val result = Yin(rate).analyse(window, NORMAL_THRESHOLD)
        val period = result as? YinResult.Period ?: failWith("expected a period, got $result")
        assertTrue("the dip counts on its interpolated minimum", period.meetsThreshold)
        assertTrue("aperiodicity ${period.aperiodicity}", period.aperiodicity < NORMAL_THRESHOLD)
        assertNote("sax-like C8 at 44.1 kHz", 108, 1.0, Pitch.nearest(period.hz, 440.0)!!)
    }

    /** The textbook sums, for the DET-04 premise: d(τ) per sample and its cumulative-mean normalisation. */
    private fun normalisedDifference(x: FloatArray, maxLag: Int): DoubleArray {
        val n = x.size
        val d = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var sum = 0.0
            for (j in 0 until n - lag) {
                val delta = x[j].toDouble() - x[j + lag]
                sum += delta * delta
            }
            d[lag] = sum / (n - lag)
        }
        val out = DoubleArray(maxLag + 1)
        out[0] = 1.0
        var running = 0.0
        for (lag in 1..maxLag) {
            running += d[lag]
            out[lag] = d[lag] * lag / running
        }
        return out
    }

    @Test
    fun `DET-08 C8 with the fundamental 20 dB under its octave reads C8`() {
        for (rate in RATES) {
            val yin = Yin(rate)
            val hz = Pitch.hzFromMidi(108, 440.0)
            val window = TestTones.harmonic(hz, rate, yin.windowFrames, doubleArrayOf(0.1, 1.0), seed = 5L)
            val note = yin.reading(window)
            assertNote("two-partial C8 at $rate", 108, 1.0, note)
        }
    }

    @Test
    fun `DET-09 nothing from F#6 to C8 reads an octave or more low`() {
        val failures = ArrayList<String>()
        for (rate in RATES) {
            val yin = Yin(rate)
            for (midi in 90..108) for (timbre in Timbre.entries) {
                val result = yin.analyse(TestTones.window(timbre, midi, 440.0, rate), NORMAL_THRESHOLD)
                val read = (result as? YinResult.Period)?.let { Pitch.nearest(it.hz, 440.0)?.midi }
                if (read == null || read <= midi - 12) failures.add("${timbre.label} MIDI $midi at $rate Hz read $read")
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `DET-10 A0 reads within 0,6 c at every A4 from 415 to 466`() {
        for (a4 in doubleArrayOf(415.0, 430.0, 435.0, 440.0, 442.0, 445.0, 450.0, 466.0)) for (rate in RATES) {
            val yin = Yin(rate)
            val note = yin.reading(TestTones.window(Timbre.SINE, 21, a4, rate), a4)
            assertNote("A0 at A4 = $a4, $rate Hz", 21, 0.6, note)
        }
    }

    /** The lowest end of the sweep: a flat A0 at A4 = 415 is still inside the nearest-note window and must read. */
    @Test
    fun `DET-10 A0 at A4 = 415 reads 40 c flat within 0,6 c`() {
        for (rate in RATES) {
            val yin = Yin(rate)
            val hz = Pitch.hzFromMidi(21 - 0.40, 415.0)
            val note = yin.reading(TestTones.sine(hz, rate, yin.windowFrames), 415.0)
            assertNote("A0 -40 c at A4 = 415, $rate Hz", 21, 40.6, note)
            assertEquals("A0 -40 c at A4 = 415, $rate Hz", -40.0, note.cents, 0.6)
        }
    }

    @Test
    fun `DET-11 C8 at A4 = 450 and at A4 = 466 reads within 0,6 c`() {
        for (a4 in doubleArrayOf(450.0, 466.0)) for (rate in RATES) {
            val yin = Yin(rate)
            val note = yin.reading(TestTones.window(Timbre.SINE, 108, a4, rate), a4)
            assertNote("C8 at A4 = $a4, $rate Hz", 108, 0.6, note)
        }
    }

    /** The highest end of the sweep: a sharp C8 at A4 = 466 is still inside the nearest-note window and must read. */
    @Test
    fun `DET-11 C8 at A4 = 466 reads 40 c sharp within 0,6 c`() {
        for (rate in RATES) {
            val yin = Yin(rate)
            val hz = Pitch.hzFromMidi(108 + 0.40, 466.0)
            val note = yin.reading(TestTones.sine(hz, rate, yin.windowFrames), 466.0)
            assertNote("C8 +40 c at A4 = 466, $rate Hz", 108, 40.6, note)
            assertEquals("C8 +40 c at A4 = 466, $rate Hz", 40.0, note.cents, 0.6)
        }
    }

    @Test
    fun `DET-12 the two rates agree within 0,2 c on every note and timbre`() {
        val yin44 = Yin(44100)
        val yin48 = Yin(48000)
        val failures = ArrayList<String>()
        for (timbre in Timbre.entries) for (midi in Pitch.DETECTOR_WINDOW) {
            val at44 = Pitch.nearest(yin44.hz(TestTones.window(timbre, midi, 440.0, 44100)), 440.0)!!
            val at48 = Pitch.nearest(yin48.hz(TestTones.window(timbre, midi, 440.0, 48000)), 440.0)!!
            val gap = abs((at44.midi * 100 + at44.cents) - (at48.midi * 100 + at48.cents))
            if (gap > 0.2) failures.add("${timbre.label} MIDI $midi: ${"%.3f".format(gap)} c apart")
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `DET-13 a 4600 Hz sine gives no reading and no sub-harmonic`() {
        for (rate in RATES) {
            val yin = Yin(rate)
            assertEquals("at $rate", YinResult.NoPeriod, yin.analyse(TestTones.sine(4600.0, rate, yin.windowFrames), NORMAL_THRESHOLD))
        }
    }

    /**
     * DET-13 across the whole octave above the band. A tone whose period is
     * shorter than the shortest lag searched still repeats at every multiple
     * of that period, and the first multiple inside the band is a dip as deep
     * as the true one; reading it would be the sub-harmonic the criterion
     * names. "No reading" is [YinResult.NoPeriod], or a deepest dip that misses
     * the threshold, which the detector then rejects (POL-10).
     */
    @Test
    fun `DET-13 every sine from 4600 Hz to 10 kHz gives no reading and no sub-harmonic, clean and at 10 dB SNR`() {
        val failures = ArrayList<String>()
        for (rate in RATES) {
            val yin = Yin(rate)
            var hz = 4600.0
            while (hz <= 10_000.0) {
                val clean = TestTones.sine(hz, rate, yin.windowFrames)
                val cases = listOf("clean" to clean) + (1L..2L).map { seed -> "at 10 dB SNR, seed $seed" to TestTones.withNoise(clean, snrDb = 10.0, seed = seed) }
                for ((label, window) in cases) {
                    val result = yin.analyse(window, NORMAL_THRESHOLD)
                    val reading = result is YinResult.Period && result.meetsThreshold
                    if (reading) failures.add("${"%.0f".format(hz)} Hz $label at $rate Hz read $result")
                }
                hz += 100.0
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    /**
     * The other side of the sub-harmonic rule: a note inside the band whose
     * fundamental is weak still has a fundamental, so its half-period dip is
     * measurably shallower than its period's, and it keeps reading as itself
     * under noise. A regression pin for DET-08 at the SNR where the DET-13
     * sweep above is proven.
     */
    @Test
    fun `DET-08 the two-partial C8 still reads C8 at 10 dB SNR`() {
        for (rate in RATES) for (seed in 1L..3L) {
            val yin = Yin(rate)
            val hz = Pitch.hzFromMidi(108, 440.0)
            val clean = TestTones.harmonic(hz, rate, yin.windowFrames, doubleArrayOf(0.1, 1.0), seed = 5L)
            val note = yin.reading(TestTones.withNoise(clean, snrDb = 10.0, seed = seed))
            assertNote("two-partial C8 at $rate, 10 dB SNR, seed $seed", 108, 1.0, note)
        }
    }

    @Test
    fun `DET-14 the window is fixed in milliseconds and covers two periods of the floor`() {
        assertEquals(2048.0 / 44100.0, Yin.HOP_SECONDS, 0.0)
        assertEquals(2.0 * Yin.HOP_SECONDS, Yin.WINDOW_SECONDS, 0.0)
        assertTrue("two periods of the floor, ${1000.0 * 2.0 / Yin.LOW_HZ} ms", Yin.WINDOW_SECONDS >= 2.0 / Yin.LOW_HZ)
        assertEquals(4096, Yin.windowFrames(44100))
        assertEquals(4458, Yin.windowFrames(48000))
        assertEquals(2048, Yin.hopFrames(44100))
        assertEquals(2229, Yin.hopFrames(48000))
        val ms44 = Yin.windowFrames(44100) * 1000.0 / 44100
        val ms48 = Yin.windowFrames(48000) * 1000.0 / 48000
        assertEquals("same length at both rates", ms44, ms48, 1000.0 / 44100)
        for (rate in RATES) {
            val yin = Yin(rate)
            assertTrue("window ${yin.windowFrames} holds two longest lags plus the neighbour at $rate", yin.windowFrames >= 2 * yin.maxLag + 2)
        }
    }

    @Test
    fun `DET-15 a non-finite window is skipped and counted and the next clean one reads`() {
        val yin = Yin(48000)
        val clean = TestTones.window(Timbre.SINE, 69, 440.0, 48000)
        assertEquals(0, yin.nonFiniteWindows)

        val withNaN = clean.copyOf().also { it[1234] = Float.NaN }
        assertEquals(YinResult.NonFinite, yin.analyse(withNaN, NORMAL_THRESHOLD))
        assertEquals(1, yin.nonFiniteWindows)

        val withInf = clean.copyOf().also { it[0] = Float.POSITIVE_INFINITY }
        assertEquals(YinResult.NonFinite, yin.analyse(withInf, NORMAL_THRESHOLD))
        val withNegInf = clean.copyOf().also { it[4457] = Float.NEGATIVE_INFINITY }
        assertEquals(YinResult.NonFinite, yin.analyse(withNegInf, NORMAL_THRESHOLD))
        assertEquals(3, yin.nonFiniteWindows)

        assertNote("A4 after the skipped windows", 69, 0.6, yin.reading(clean))
        assertEquals(3, yin.nonFiniteWindows)
    }

    @Test
    fun `silence and noise give no reading that meets the threshold`() {
        val yin = Yin(48000)
        assertEquals(YinResult.NoPeriod, yin.analyse(FloatArray(yin.windowFrames), NORMAL_THRESHOLD))
        for (seed in 1L..5L) {
            val result = yin.analyse(TestTones.noise(yin.windowFrames, seed = seed), NORMAL_THRESHOLD)
            if (result is YinResult.Period) {
                assertTrue("noise seed $seed read ${result.hz} Hz with aperiodicity ${result.aperiodicity}", !result.meetsThreshold)
            }
        }
    }

    @Test
    fun `a window of the wrong length is refused`() {
        val yin = Yin(48000)
        val failure = runCatching { yin.analyse(FloatArray(4096), NORMAL_THRESHOLD) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }

    /**
     * DET-17 is a device gate at M2. On the JVM this number is informational:
     * it says where the Kotlin estimator stands before the reference phone
     * decides between Kotlin and a C++ move (decision 3). Noise windows are the
     * costly case, because every dip in the search range is examined.
     */
    @Test
    fun `DET-17 per-hop cost on the JVM, informational`() {
        val yin = Yin(48000)
        val tone = TestTones.window(Timbre.SAX_LIKE, 69, 440.0, 48000)
        val noise = TestTones.noise(yin.windowFrames)
        repeat(50) {
            yin.analyse(tone, NORMAL_THRESHOLD)
            yin.analyse(noise, NORMAL_THRESHOLD)
        }
        fun measure(window: FloatArray, hops: Int): DoubleArray {
            val costs = DoubleArray(hops)
            for (i in 0 until hops) {
                val start = System.nanoTime()
                yin.analyse(window, NORMAL_THRESHOLD)
                costs[i] = (System.nanoTime() - start) / 1e6
            }
            costs.sort()
            return costs
        }
        val toneCosts = measure(tone, 200)
        val noiseCosts = measure(noise, 200)
        fun report(label: String, costs: DoubleArray) =
            "$label: median ${"%.2f".format(costs[costs.size / 2])} ms, p99 ${"%.2f".format(costs[(costs.size * 99) / 100])} ms, max ${"%.2f".format(costs.last())} ms"
        println("DET-17 JVM per-hop cost, 48 kHz, ${yin.windowFrames}-frame window, ${yin.fftLength}-point FFT")
        println("  " + report("sax-like A4", toneCosts))
        println("  " + report("white noise", noiseCosts))
        assertTrue("the benchmark ran", toneCosts.size == 200 && noiseCosts.size == 200)
    }
}
