// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.yin

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ticktock.core.music.Pitch

/**
 * DET-06 and DET-07: every MIDI note from 21 to 108 at 44.1 and 48 kHz, as a
 * sine (176 cases, ±0.6 c) and as the two instrument-like timbres (352 cases,
 * ±1 c). Each case is its own test, named after the criterion, the timbre,
 * the note and the rate, so one failure leaves the other 527 visible.
 */
@RunWith(Parameterized::class)
class YinSweepTest(private val case: SweepCase) {
    data class SweepCase(val criterion: String, val timbre: Timbre, val midi: Int, val sampleRate: Int, val toleranceCents: Double) {
        override fun toString() = "$criterion ${timbre.label} MIDI $midi (${Pitch.label(midi)}) at $sampleRate Hz"
    }

    @Test
    fun `reads the note within tolerance`() {
        val yin = yins.getValue(case.sampleRate)
        val note = yin.reading(TestTones.window(case.timbre, case.midi, A4, case.sampleRate), A4)
        assertNote(case.toString(), case.midi, case.toleranceCents, note)
    }

    companion object {
        private const val A4 = 440.0
        private val yins: Map<Int, Yin> = RATES.associateWith { Yin(it) }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<SweepCase> {
            val out = ArrayList<SweepCase>()
            for (rate in RATES) for (midi in Pitch.DETECTOR_WINDOW) out.add(SweepCase("DET-06", Timbre.SINE, midi, rate, 0.6))
            for (timbre in listOf(Timbre.SAX_LIKE, Timbre.WEAK_FUNDAMENTAL)) {
                for (rate in RATES) for (midi in Pitch.DETECTOR_WINDOW) out.add(SweepCase("DET-07", timbre, midi, rate, 1.0))
            }
            check(out.size == 528) { "the sweep has ${out.size} cases, the spec names 528" }
            return out
        }
    }
}
