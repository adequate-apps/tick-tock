// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import org.junit.Assert.assertEquals
import org.junit.Test
import ticktock.core.music.NearestNote
import ticktock.core.music.Pitch
import ticktock.core.yin.NORMAL_THRESHOLD
import ticktock.core.yin.TestTones
import ticktock.core.yin.Timbre

/**
 * DET-06 and DET-07 on the production path: the same 528 cases as
 * YinSweepTest, read through [YinEstimator], which is YIN followed by the
 * harmonic refinement. The bare-YIN sweep proves the period search; this one
 * proves what the app reads. The lowest octave and a half is also played at
 * eight starting phases, because a short window's error there depends on
 * where in its cycle the tone starts.
 */
class EstimatorSweepTest {
    private val rates = listOf(44_100, 48_000)
    private val a4 = 440.0

    private fun read(timbre: Timbre, midi: Int, rate: Int, phaseSeed: Long): NearestNote? {
        val hz = Pitch.hzFromMidi(midi, a4)
        val window = TestTones.harmonic(hz, rate, Hop.windowFrames(rate), timbre.partials, seed = phaseSeed)
        val estimate = YinEstimator(NORMAL_THRESHOLD).estimate(window, window.size, rate)
        return Pitch.nearest(estimate.hz, a4)
    }

    private fun sweep(midis: IntRange, seeds: List<Long>): List<String> {
        val problems = ArrayList<String>()
        for (rate in rates) for (timbre in Timbre.entries) for (midi in midis) for (seed in seeds) {
            val tolerance = if (timbre == Timbre.SINE) 0.6 else 1.0
            val note = read(timbre, midi, rate, seed)
            val label = "${if (timbre == Timbre.SINE) "DET-06" else "DET-07"} ${timbre.label} MIDI $midi at $rate Hz, phase seed $seed"
            when {
                note == null -> problems += "$label: no note"
                note.midi != midi -> problems += "$label: read MIDI ${note.midi} at %.2f c".format(note.cents)
                kotlin.math.abs(note.cents) > tolerance -> problems += "$label: %.3f c outside ±$tolerance".format(note.cents)
            }
        }
        return problems
    }

    @Test
    fun `DET-06 and DET-07 every note from A0 to C8 reads within tolerance through the estimator`() {
        val problems = sweep(Pitch.DETECTOR_WINDOW, listOf(0L)) +
            // TestTones.window's own phases, seed 1000 + midi, as the bare sweep uses.
            Pitch.DETECTOR_WINDOW.flatMap { midi -> sweep(midi..midi, listOf(1000L + midi)) }
        assertEquals(problems.take(20).joinToString("\n"), 0, problems.size)
    }

    @Test
    fun `DET-06 and DET-07 the lowest notes read within tolerance whatever phase they start at`() {
        val problems = sweep(21..40, (1L..8L).toList())
        assertEquals(problems.take(20).joinToString("\n"), 0, problems.size)
    }

    @Test
    fun `a note a hair under the quarter-tone stays the note YIN found`() {
        for (rate in rates) for (timbre in listOf(Timbre.SAX_LIKE, Timbre.WEAK_FUNDAMENTAL)) for (midi in listOf(21, 33, 45, 69)) {
            val hz = Pitch.hzFromMidi(midi + 0.49, a4)
            val window = TestTones.harmonic(hz, rate, Hop.windowFrames(rate), timbre.partials)
            val estimate = YinEstimator(NORMAL_THRESHOLD).estimate(window, window.size, rate)
            assertEquals("${timbre.label} MIDI $midi +49 c at $rate Hz", midi, Pitch.nearest(estimate.hz, a4)?.midi)
        }
    }
}
