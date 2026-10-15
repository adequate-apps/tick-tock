// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.play

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Simulated playing, graded against the truth. Each take is a scored passage
 * on a modelled wind instrument in a room, rendered with its exact pitch
 * track and played through the real capture path, YIN and the detector at
 * 44.1 and 48 kHz. The bounds below are the accuracy the app promises:
 * - no reading ever names a note that was not sounding;
 * - a reading inside a held note, past its attack, is within [STEADY_CENTS]
 *   of what was played, and the printed whole cent is the played pitch rounded;
 * - a note's mean (what the table keeps) is within [NOTE_MEAN_CENTS];
 * - vibrato and a harsh room have their own bounds, below, each set from
 *   measurement with headroom and each explained;
 * - every note long enough to read is read, within [LATENCY_MS] of its onset.
 * Vibrato and drift takes are graded against the mean pitch over each
 * reading's own window, which is what a tuner can honestly report.
 *
 * The report and the takes as WAV files go to build/play-report/.
 */
class PlayTest {
    private fun take(
        title: String,
        instrument: Instrument,
        score: List<Event>,
        a4: Double = 440.0,
        room: Room = Room(),
        steady: Double = STEADY_CENTS,
        noteMean: Double = NOTE_MEAN_CENTS,
        exactPrint: Boolean = true,
    ) {
        val problems = mutableListOf<String>()
        for (rate in RATES) {
            val performance = Performer(instrument, rate, a4, seed = title.hashCode()).render(score, room)
            val rig = PlayRig(performance, seed = rate).play()
            val card = Scorecard(performance, rig, "$title, $rate Hz")
            report += card.summary()
            worst += card.worst()
            if (rate == RATES.last()) writeWav(File(REPORT_DIR, "${title.replace(Regex("[^a-z0-9]+"), "-")}.wav"), performance)

            val label = card.title
            if (card.phantoms.isNotEmpty()) problems += "$label: phantom notes ${card.phantoms.take(5)}"
            if (card.wrongNotes.isNotEmpty()) problems += "$label: wrong notes ${card.wrongNotes.take(5)}"
            if (card.maxError > steady) problems += "$label: worst held-note error %.2f c, bound %.2f".format(card.maxError, steady)
            if (exactPrint && card.shownOff > 0) problems += "$label: ${card.shownOff} printed cents off by more than rounding"
            if (card.maxNoteMeanError > noteMean) problems += "$label: worst note mean error %.2f c, bound %.2f".format(card.maxNoteMeanError, noteMean)
            if (card.missedNotes.isNotEmpty()) problems += "$label: notes never read ${card.missedNotes}"
            if ((card.latenciesMs.maxOrNull() ?: 0.0) > LATENCY_MS) problems += "$label: worst latency ${card.latenciesMs.maxOrNull()} ms"
            if (rig.analysisErrors != 0L) problems += "$label: ${rig.analysisErrors} analysis errors"
        }
        assertEquals(emptyList<String>(), problems)
    }

    private fun longTones(midis: List<Int>, seconds: Double = 2.0, detunes: List<Double> = DETUNES, level: Double = 0.25): List<Event> =
        midis.flatMapIndexed { i, midi -> listOf(Play(Note(midi, seconds, cents = detunes[i % detunes.size], level = level)), Rest(0.4)) }

    @Test
    fun `long tones across every instrument's range, sharp and flat`() {
        take("alto sax long tones", Instrument.ALTO_SAX, longTones(listOf(49, 56, 63, 69, 75, 81)))
        take("clarinet long tones", Instrument.CLARINET, longTones(listOf(50, 58, 66, 74, 82, 89)))
        take("flute long tones", Instrument.FLUTE, longTones(listOf(60, 67, 74, 81, 88, 96)))
        take("trumpet long tones", Instrument.TRUMPET, longTones(listOf(52, 58, 64, 70, 76, 82)))
        take("bari sax long tones", Instrument.BARI_SAX, longTones(listOf(36, 41, 48, 55, 62, 69)))
    }

    @Test
    fun `a tongued chromatic octave at quarter = 120`() {
        val score = (57..69).flatMap { listOf(Play(Note(it, 0.46, cents = ((it % 5) - 2) * 4.0)), Rest(0.04)) }
        take("alto sax chromatic", Instrument.ALTO_SAX, score)
    }

    @Test
    fun `a slurred arpeggio reads every note with no phantom between them`() {
        val notes = listOf(55, 59, 62, 67, 71, 74, 79, 74, 71, 67, 62, 59, 55)
        val score = notes.mapIndexed { i, m -> Play(Note(m, 0.6, cents = ((i % 3) - 1) * 6.0, articulation = if (i == 0) Articulation.TONGUED else Articulation.SLURRED)) } + Rest(0.5)
        take("clarinet slurred arpeggio", Instrument.CLARINET, score)
    }

    @Test
    fun `vibrato long tones keep their centre`() {
        val vib = { m: Int, c: Double -> listOf(Play(Note(m, 3.0, cents = c, vibratoCents = 15.0, vibratoHz = 5.5)), Rest(0.4)) }
        take("alto sax vibrato", Instrument.ALTO_SAX, vib(61, -4.0) + vib(68, 9.0) + vib(73, 0.0), steady = VIBRATO_STEADY_CENTS, noteMean = VIBRATO_CENTRE_CENTS, exactPrint = false)
        take("flute vibrato", Instrument.FLUTE, vib(72, 3.0) + vib(79, -11.0) + vib(86, 6.0), steady = VIBRATO_STEADY_CENTS, noteMean = VIBRATO_CENTRE_CENTS, exactPrint = false)
    }

    @Test
    fun `a long tone that sags flat is followed cent by cent`() {
        val score = listOf(Play(Note(67, 5.0, cents = 4.0, driftCents = -22.0)), Rest(0.4), Play(Note(74, 5.0, cents = -6.0, driftCents = 14.0, level = 0.08)), Rest(0.4))
        take("flute sagging long tones", Instrument.FLUTE, score)
    }

    @Test
    fun `quiet and loud playing read alike`() {
        take("alto sax pianissimo", Instrument.ALTO_SAX, longTones(listOf(52, 64, 76), level = 0.02))
        take("trumpet fortissimo", Instrument.TRUMPET, longTones(listOf(55, 67, 79), level = 0.8))
    }

    @Test
    fun `a noisy room with hum, hiss and a live hall`() {
        val room = Room(noiseDb = -42.0, humDb = -38.0, reverbDb = -8.0, reverbSeconds = 1.2)
        take("alto sax noisy hall", Instrument.ALTO_SAX, longTones(listOf(49, 58, 65, 72, 80)), room = room, steady = HALL_STEADY_CENTS, exactPrint = false)
        take("bari sax noisy hall", Instrument.BARI_SAX, longTones(listOf(36, 43, 50, 57)), room = room, steady = HALL_STEADY_CENTS, exactPrint = false)
    }

    @Test
    fun `every A4 from baroque to high pitch`() {
        for (a4 in listOf(415.0, 432.0, 442.0, 466.0)) {
            take("alto sax at A4 ${a4.toInt()}", Instrument.ALTO_SAX, longTones(listOf(55, 66, 77), seconds = 1.5), a4 = a4)
        }
    }

    @Test
    fun `fast sixteenths at quarter = 120 never invent a note`() {
        val run = listOf(60, 62, 64, 65, 67, 69, 71, 72, 71, 69, 67, 65, 64, 62, 60, 62)
        val score = run.mapIndexed { i, m -> Play(Note(m, 0.125, articulation = if (i % 4 == 0) Articulation.TONGUED else Articulation.SLURRED)) } +
            listOf(Play(Note(60, 1.5, articulation = Articulation.SLURRED)), Rest(0.5))
        take("alto sax sixteenths", Instrument.ALTO_SAX, score)
    }

    private fun writeWav(file: File, p: Performance) {
        file.parentFile.mkdirs()
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            val bytes = p.samples.size * 2
            fun le32(v: Int) = out.writeInt(Integer.reverseBytes(v))
            fun le16(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            out.writeBytes("RIFF"); le32(36 + bytes); out.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1)
            le32(p.sampleRate); le32(p.sampleRate * 2); le16(2); le16(16); out.writeBytes("data"); le32(bytes)
            for (s in p.samples) le16((s.coerceIn(-1f, 1f) * 32767).toInt())
        }
    }

    companion object {
        private val RATES = listOf(44_100, 48_000)
        private val DETUNES = listOf(-23.0, -7.0, 0.0, 4.0, 17.0, -2.0)
        private val REPORT_DIR = File("build/play-report")
        private val report = mutableListOf<String>()
        private val worst = mutableListOf<String>()

        /** A held note, past its attack, in a room a player practises in. Measured worst: 0.15 c. */
        const val STEADY_CENTS = 0.25

        /** The mean of a note's readings, as the table keeps it. Measured worst: 0.06 c. */
        const val NOTE_MEAN_CENTS = 0.1

        /**
         * A reading under ±15 c vibrato. Half a vibrato cycle fits in one window,
         * and the reading lands where the pitch dwells in it, between the window's
         * mean and its crest: measured worst 3.1 c from the mean.
         */
        const val VIBRATO_STEADY_CENTS = 4.0

        /** A vibrato note's centre, the mean of its readings. Measured worst: 0.1 c. */
        const val VIBRATO_CENTRE_CENTS = 0.3

        /**
         * A held note in a live hall with hiss and mains hum 38 dB down. The hum
         * sits a few hertz from the lowest notes and moves a reading; measured
         * worst 0.66 c, so the printed whole cent can be one off at a rounding edge.
         */
        const val HALL_STEADY_CENTS = 1.0

        const val LATENCY_MS = 450.0

        @JvmStatic
        @AfterClass
        fun writeReport() {
            REPORT_DIR.mkdirs()
            File(REPORT_DIR, "report.md").writeText(
                "# Simulated play tests\n\nCents are errors against the played pitch over each reading's window; clean readings only.\n\n" +
                    Scorecard.HEADER + "\n" + report.joinToString("\n") + "\n\n## Worst held-note readings\n\n" +
                    worst.joinToString("\n") { "- $it" } + "\n",
            )
        }
    }
}
