// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.A4Generation
import ticktock.core.detect.Decision
import ticktock.core.detect.Detector
import ticktock.core.detect.Hop
import ticktock.core.detect.HopAnalyser
import ticktock.core.detect.YinEstimator
import ticktock.core.play.Articulation
import ticktock.core.play.Event
import ticktock.core.play.Instrument
import ticktock.core.play.Note
import ticktock.core.play.Performer
import ticktock.core.play.Play
import ticktock.core.play.Rest
import ticktock.core.play.Room
import ticktock.core.play.Scorecard
import ticktock.engine.input.AnalysisThread

/** The desktop scorecard grades Android decisions against the independent sample pitch track. */
@RunWith(AndroidJUnit4::class)
class PassageAudioTest {
    private data class Take(
        val name: String,
        val instrument: Instrument,
        val score: List<Event>,
        val room: Room = Room(),
        val steadyCents: Double = 0.25,
        val meanCents: Double = 0.05,
        val exactPrint: Boolean = true,
    )

    @Test fun scoredPassages() {
        val takes = listOf(
            Take("clarinet-slur", Instrument.CLARINET,
                listOf(55, 59, 62, 67, 71, 74, 79, 74, 71, 67, 62, 59, 55).mapIndexed { i, midi ->
                    Play(Note(midi, 0.6, cents = ((i % 3) - 1) * 6.0,
                        articulation = if (i == 0) Articulation.TONGUED else Articulation.SLURRED))
                } + Rest(0.5)),
            Take("sax-vibrato", Instrument.ALTO_SAX,
                listOf(61 to -4.0, 68 to 9.0, 73 to 0.0).flatMap { (midi, cents) ->
                    listOf(Play(Note(midi, 3.0, cents = cents, vibratoCents = 15.0)), Rest(0.4))
                }, steadyCents = 4.0, meanCents = 0.3, exactPrint = false),
            Take("flute-drift", Instrument.FLUTE, listOf(
                Play(Note(67, 5.0, cents = 4.0, driftCents = -22.0)), Rest(0.4),
                Play(Note(74, 5.0, cents = -6.0, driftCents = 14.0, level = 0.08)), Rest(0.4))),
            Take("bari-noisy-hall", Instrument.BARI_SAX,
                listOf(36, 43, 50, 57).flatMapIndexed { i, midi ->
                    listOf(Play(Note(midi, 2.0, cents = listOf(-23.0, -7.0, 0.0, 4.0)[i])), Rest(0.4))
                }, Room(noiseDb = -42.0, humDb = -38.0, reverbDb = -8.0, reverbSeconds = 1.2),
                steadyCents = 1.0, meanCents = 0.25, exactPrint = false),
            Take("sax-sixteenths", Instrument.ALTO_SAX,
                listOf(60, 62, 64, 65, 67, 69, 71, 72, 71, 69, 67, 65, 64, 62, 60, 62).mapIndexed { i, midi ->
                    Play(Note(midi, 0.125,
                        articulation = if (i % 4 == 0) Articulation.TONGUED else Articulation.SLURRED))
                } + listOf(Play(Note(60, 1.5, articulation = Articulation.SLURRED)), Rest(0.5))),
        )
        val rows = mutableListOf(Scorecard.HEADER)
        val hashes = mutableListOf("take,rate,seed,pcm_sha256,hops")
        val failures = mutableListOf<String>()
        for (take in takes) for (rate in listOf(44100, 48000)) {
            val label = "${take.name}-$rate"
            val seed = take.name.hashCode()
            val performance = Performer(take.instrument, rate, 440.0, seed).render(take.score, take.room)
            val detector = Detector(a4 = A4Generation(performance.a4Hz))
            val input = FakeAudioInput(rate)
            val queue = LinkedBlockingQueue<List<Decision>>()
            val worker = AnalysisThread(HopAnalyser(detector, YinEstimator(detector)),
                promote = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }, onDecisions = queue::add)
            val decisions = mutableListOf<Decision>()
            val frames = Hop.frames(rate)
            val hops = performance.samples.size / frames
            try {
                worker.start(input).getOrThrow()
                repeat(hops) { index ->
                    assertTrue("$label unexpected prior decisions", queue.isEmpty())
                    assertTrue(input.push(performance.samples.copyOfRange(index * frames, (index + 1) * frames)))
                    val batch = queue.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("$label hop $index timed out")
                    assertEquals(1, batch.size)
                    assertEquals(index.toLong(), batch.single().hop)
                    decisions += batch.single()
                }
                assertEquals(hops.toLong(), worker.counters.hopsAnalysed)
                assertEquals(0L, worker.counters.analysisErrors)
                assertEquals(0L, worker.counters.droppedHops)
            } finally { worker.stop() }
            val card = Scorecard(performance, decisions, label)
            rows += card.summary()
            val digest = MessageDigest.getInstance("SHA-256")
            performance.samples.forEach { sample ->
                val bits = sample.toRawBits()
                repeat(4) { digest.update((bits ushr (it * 8)).toByte()) }
            }
            val hash = digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }
            hashes += "$label,$rate,$seed,$hash,$hops"
            fun requireCase(ok: Boolean, reason: String) { if (!ok) failures += "$label: $reason" }
            requireCase(card.graded.any { it.clean }, "no clean readings")
            requireCase(card.phantoms.isEmpty() && card.wrongNotes.isEmpty(), "wrong or phantom notes: ${card.phantoms + card.wrongNotes}")
            requireCase(card.maxError <= take.steadyCents, "held error ${card.maxError} > ${take.steadyCents} cents")
            requireCase(card.maxNoteMeanError <= take.meanCents, "note mean ${card.maxNoteMeanError} > ${take.meanCents} cents")
            requireCase(!take.exactPrint || card.shownOff == 0, "${card.shownOff} incorrect whole-cent readings")
            requireCase(card.missedNotes.isEmpty(), "missed notes ${card.missedNotes}")
            val readable = performance.notes.count { it.seconds >= Scorecard.READABLE_SECONDS }
            requireCase(card.readoutLatenciesMs.size == readable, "missing readable-note attacks")
            requireCase((card.readoutLatenciesMs.maxOrNull() ?: 0.0) <= 400.0, "readout latency over 400 ms")
            requireCase((card.latenciesMs.maxOrNull() ?: 0.0) <= 500.0, "saved latency over 500 ms")
        }
        val evidence = AudioEvidence.directory()
        File(evidence, "passage-scorecard.md").writeText(rows.joinToString("\n") + "\n")
        File(evidence, "passage-corpus.csv").writeText(hashes.joinToString("\n") + "\n")
        File(evidence, "passage-failures.txt").writeText(failures.joinToString("\n"))
        assertEquals(10, hashes.size - 1)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
