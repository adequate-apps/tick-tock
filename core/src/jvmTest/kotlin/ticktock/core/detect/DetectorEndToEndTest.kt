// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.fakes.Synth
import ticktock.core.music.Pitch
import ticktock.core.yin.Timbre
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The whole M1 path on audio: a fake microphone delivers PCM in 10 ms
 * blocks, [HopAnalyser] cuts hops, the real YIN estimates, the [Detector]
 * decides. Nothing in between is scripted, so these tests are the first
 * place a disagreement between the estimator's units and the policy's would
 * show.
 */
class DetectorEndToEndTest {
    private val rates = listOf(44_100, 48_000)
    private val a4 = 442.0

    /** Alto saxophone sounding compass, D flat 3 to A5: 33 notes, as ATK-03's sweep. */
    private val run = 49..81

    /** The run alternates flat, in tune and sharp so the cents are measured, never assumed. */
    private fun detuneCents(index: Int): Double = ((index % 3) - 1) * 7.0

    private class Rig(rate: Int, a4Hz: Double) {
        val detector = Detector(ResponseMode.NORMAL, a4 = A4Generation(a4Hz))
        val estimator = YinEstimator(detector)
        val analyser = HopAnalyser(detector, estimator)
        val input = FakeAudioInput(sampleRate = rate)
        val decisions = ArrayList<Decision>()
        val blockFrames = rate / 100

        init {
            analyser.attach(input).getOrThrow()
        }

        /** Delivers [samples] the way a capture thread would, a block at a time, draining after each. */
        fun play(samples: FloatArray) {
            var offset = 0
            while (offset < samples.size) {
                val frames = minOf(blockFrames, samples.size - offset)
                input.push(samples.copyOfRange(offset, offset + frames), frames)
                decisions += analyser.drain()
                offset += frames
            }
        }

        val saved: List<Reading> get() = decisions.mapNotNull { it.saved }
        val attacks: List<Attack> get() = decisions.mapNotNull { it.attack }
    }

    @Test
    fun `a chromatic saxophone-like run at A4 = 442 reads every note and its cents through the real YIN`() {
        for (rate in rates) {
            val rig = Rig(rate, a4)
            val voice = HarmonicVoice(Timbre.SAX_LIKE.partials, rate, peak = 0.3)
            val holdFrames = Hop.count(1.0) * Hop.frames(rate)
            for ((index, midi) in run.withIndex()) {
                voice.hz = Pitch.hzFromMidi(midi + detuneCents(index) / 100.0, a4)
                rig.play(voice.next(holdFrames))
            }
            rig.play(Synth.silence(Hop.frames(rate) * Hop.WINDOW_HOPS))

            val label = "$rate Hz"
            assertEquals("$label: one attack per note, in order", run.toList(), rig.attacks.map { it.midiSounding })

            val byNote = rig.saved.groupBy { it.midiSounding }
            assertEquals("$label: every note of the run was saved and nothing else", run.toList(), byNote.keys.sorted())
            for ((index, midi) in run.withIndex()) {
                val readings = byNote.getValue(midi)
                val expected = detuneCents(index)
                assertTrue("$label ${Pitch.label(midi)}: ${readings.size} saved readings", readings.size >= 10)
                for (reading in readings) {
                    assertEquals("$label ${Pitch.label(midi)} hop ${reading.hop}: cents", expected, reading.cents, 1.0)
                    assertEquals("$label ${Pitch.label(midi)} hop ${reading.hop}: hz", Pitch.hzFromMidi(midi + expected / 100.0, a4), reading.hz, Pitch.hzFromMidi(midi, a4) * 0.0006)
                    assertEquals("$label: readings carry their A4", a4, reading.a4Hz, 0.0)
                    assertEquals("$label: readings carry their generation", rig.detector.a4.generation, reading.a4Generation)
                }
            }

            // Legato: once the first note has won, the readout is never blank
            // between two notes (POL-02 on real audio), and no analysis failed.
            val firstAttack = rig.decisions.indexOfFirst { it.attack != null }
            val lastRelease = rig.decisions.indexOfLast { it.release != null }
            for (decision in rig.decisions.subList(firstAttack, lastRelease)) {
                assertNotNull("$label hop ${decision.hop}: blank readout mid-run", decision.incumbent)
            }
            assertEquals("$label: analysis errors", 0, rig.analyser.counters.analysisErrors)
            assertEquals("$label: dropped hops", 0, rig.analyser.counters.droppedHops)
            assertEquals("$label: non-finite windows", 0, rig.estimator.nonFiniteWindows)
        }
    }

    @Test
    fun `POL-10 ten seconds of white noise at -20 dBFS through the real YIN saves nothing and shows no note`() {
        for (rate in rates) {
            val rig = Rig(rate, a4)
            rig.play(Synth.noise(rate * 10, rms = 0.1, seed = rate))
            val label = "$rate Hz"
            assertTrue("$label: hops analysed", rig.analyser.counters.hopsAnalysed >= Hop.count(10.0) - 1)
            assertEquals("$label: saved readings", emptyList<Reading>(), rig.saved)
            assertEquals("$label: attacks", emptyList<Attack>(), rig.attacks)
            assertTrue("$label: a note was shown", rig.decisions.all { it.incumbent == null })
            // Hop 0's window is half the silence before the stream, so its level is lower by design.
            assertTrue("$label: the meter saw the noise", rig.decisions.drop(1).all { abs(it.level - 0.1) < 0.02 })
        }
    }

    @Test
    fun `DET-15 a hop holding a NaN is skipped and counted and the held note goes on`() {
        for (rate in rates) {
            val rig = Rig(rate, a4)
            val voice = HarmonicVoice(Timbre.SAX_LIKE.partials, rate, peak = 0.3)
            voice.hz = Pitch.hzFromMidi(69, a4)
            val hopFrames = Hop.frames(rate)
            rig.play(voice.next(hopFrames * 12))
            val before = rig.saved.size
            assertTrue("$rate Hz: readings before the bad hop", before > 0)

            val poisoned = voice.next(hopFrames).also { it[hopFrames / 2] = Float.NaN }
            rig.play(poisoned)
            rig.play(voice.next(hopFrames * 12))

            val label = "$rate Hz"
            // The bad hop sits in two windows: its own and the next hop's, whose window starts with it.
            assertEquals("$label: non-finite windows", 2, rig.estimator.nonFiniteWindows)
            assertEquals("$label: analysis errors", 0, rig.analyser.counters.analysisErrors)
            assertTrue("$label: readings after the bad hop", rig.saved.size > before)
            assertEquals("$label: one attack, the note survived the gap", 1, rig.attacks.size)
            assertTrue("$label: every saved reading is A4", rig.saved.all { it.midiSounding == 69 && abs(it.cents) <= 1.0 })
            assertTrue("$label: the readout held", rig.decisions.drop(rig.decisions.indexOfFirst { it.attack != null }).all { it.incumbent == 69 })
        }
    }
}

/**
 * A phase-continuous sum of partials, so a run of notes is one legato line
 * the way a reed delivers it: changing [hz] bends every partial from where
 * it is, with no click and no restart. Partials at or above Nyquist are
 * left out, as a microphone would. The peak is bounded by [peak].
 */
private class HarmonicVoice(private val partials: DoubleArray, private val sampleRate: Int, private val peak: Double) {
    var hz = 0.0
    private val phases = DoubleArray(partials.size)
    private val gain = peak / partials.sum()

    fun next(frames: Int): FloatArray {
        val out = FloatArray(frames)
        val nyquist = sampleRate / 2.0
        for (i in 0 until frames) {
            var sample = 0.0
            for (p in partials.indices) {
                val partialHz = hz * (p + 1)
                if (partialHz >= nyquist) continue
                sample += partials[p] * sin(phases[p])
                phases[p] += 2.0 * PI * partialHz / sampleRate
                if (phases[p] >= 2.0 * PI) phases[p] -= 2.0 * PI
            }
            out[i] = (sample * gain).toFloat()
        }
        return out
    }
}
