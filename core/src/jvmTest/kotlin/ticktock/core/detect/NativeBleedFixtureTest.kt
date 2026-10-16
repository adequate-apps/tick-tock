// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.contracts.fakes.FakeSoundingNotes

/** Native ramp/voice samples cross the process boundary before the production estimator sees them. */
class NativeBleedFixtureTest {
    @Test(timeout = 120_000)
    fun `POL-24 native drone alone saves and displays nothing for ten seconds`() = variants { rate, voice ->
        Rig(rate, voice).use { rig ->
            rig.run(ceil(10.0 * rate / rig.hopFrames).toInt())
            assertTrue(rig.label, rig.decisions.isNotEmpty())
            rig.assertNoPlayer()
            assertEquals(rig.label, listOf(0.30f), rig.requestedGains)
            assertTrue(rig.label, rig.completions.isNotEmpty())
            rig.assertHealthy()
        }
    }

    @Test(timeout = 120_000)
    fun `POL-27 a real player in native drone unison reaches history only after ramp evidence`() = variants { rate, voice ->
        Rig(rate, voice, playerAmplitude = 0.5).use { rig ->
            rig.run(60)
            val readings = rig.decisions.mapNotNull { it.saved }
            assertTrue("${rig.label}: the player must reach history", readings.size >= 10)
            assertTrue(rig.label, readings.all { it.midiSounding == MIDI && abs(it.cents) < 1.0 })
            assertEquals(rig.label, listOf(0.30f), rig.requestedGains)
            assertTrue(rig.label, rig.decisions.takeWhile { it.hop < rig.firstAcknowledgedHop }.all { it.saved == null })
            assertEquals(rig.label, MIDI, rig.detector.shownNote)
            rig.assertHealthy()
        }
    }

    @Test(timeout = 120_000)
    fun `POL-28 delayed native completion and buffered leakage never establish a player`() = variants { rate, voice ->
        Rig(rate, voice, acknowledgementDelayHops = 8, inputBufferHops = 2).use { rig ->
            rig.run(80)
            assertTrue(rig.label, rig.firstAcknowledgedHop >= 8)
            rig.assertNoPlayer()
            assertNull(rig.label, rig.detector.bleedState.admitted)
            assertEquals(rig.label, listOf(0.30f), rig.requestedGains)
            rig.assertHealthy()
        }
    }

    @Test(timeout = 120_000)
    fun `POL-26 native duck completion is about eighty milliseconds after the request`() {
        for (rate in RATES) NativeDriver(rate, 0).use { driver ->
            driver.render(rate / 10)
            val requestedFrame = driver.position
            driver.duck(0.30f, 1)
            val completions = mutableListOf<Completion>()
            repeat(3) { completions += driver.render(Hop.frames(rate)).completions }
            assertEquals("rate $rate: one completion", 1, completions.size)
            val milliseconds = (completions.single().frame - requestedFrame) * 1_000.0 / rate
            assertTrue("rate $rate: duck completed after $milliseconds ms, expected about 80 ms",
                milliseconds in 75.0..85.0)
        }
    }

    private fun variants(block: (Int, Int) -> Unit) {
        for (rate in RATES) for (voice in 0..2) block(rate, voice)
    }

    private class Rig(
        val rate: Int,
        voice: Int,
        private val playerAmplitude: Double = 0.0,
        private val acknowledgementDelayHops: Int = 0,
        inputBufferHops: Int = 0,
    ) : AutoCloseable {
        val label = "rate $rate voice $voice"
        val hopFrames = Hop.frames(rate)
        // Match ToolState.soundingMidi: the root-and-fifth voice exposes both sounding notes.
        private val sounding = FakeSoundingNotes(*if (voice == 2) intArrayOf(MIDI, MIDI + 7) else intArrayOf(MIDI))
        val detector = Detector(a4 = A4Generation(A4.toDouble()), sounding = sounding)
        private val analyser = HopAnalyser(detector, YinEstimator(detector))
        private val input = FakeAudioInput(rate, bufferFrames = inputBufferHops * hopFrames)
        private val native = NativeDriver(rate, voice)
        private var generation = 0
        private var samplePosition = 0L
        private var hop = 0
        private val acknowledgements = mutableMapOf<Int, () -> Unit>()
        private val due = mutableListOf<Pair<Int, () -> Unit>>()
        val decisions = mutableListOf<Decision>()
        val requestedGains = mutableListOf<Float>()
        val completions = mutableListOf<Completion>()
        var firstAcknowledgedHop = Long.MAX_VALUE
            private set

        init { analyser.attach(input).getOrThrow() }

        fun run(hops: Int) {
            repeat(hops) {
                val rendered = native.render(hopFrames)
                completions += rendered.completions
                // Independent score truth: a player at the target frequency, unaffected by output gain.
                val hz = A4 * 2.0.pow((MIDI - 69) / 12.0)
                val mixed = FloatArray(hopFrames) { i ->
                    val player = playerAmplitude * sin(2.0 * PI * hz * (samplePosition + i) / rate)
                    (rendered.samples[i] + player).toFloat().also { require(abs(it) < 1f) }
                }
                samplePosition += hopFrames
                assertTrue(input.push(mixed))
                val batch = analyser.drain()
                decisions += batch
                for (decision in batch) decision.duck?.let { gain ->
                    requestedGains += gain
                    generation++
                    acknowledgements[generation] = analyser.deferBleedEvidence()
                    native.duck(gain, generation)
                }
                for (completed in rendered.completions) {
                    val acknowledge = acknowledgements.remove(completed.generation)
                        ?: error("$label: unexpected native generation ${completed.generation}")
                    due += (hop + acknowledgementDelayHops) to acknowledge
                }
                // Simulated zero output latency: delivery occurs at the rendered block boundary.
                // HopAnalyser additionally excludes upstream capture buffering and overlapping windows.
                val ready = due.filter { it.first <= hop }
                due.removeAll(ready.toSet())
                for ((_, acknowledge) in ready) {
                    acknowledge()
                    firstAcknowledgedHop = minOf(firstAcknowledgedHop, hop.toLong())
                }
                hop++
            }
        }

        fun assertHealthy() {
            assertEquals(label, 0L, analyser.counters.droppedHops)
            assertEquals(label, 0L, analyser.counters.analysisErrors)
            assertFalse(label, decisions.isEmpty())
        }

        fun assertNoPlayer() {
            val unwanted = decisions.filter { it.saved != null || it.incumbent != null }
            assertTrue("$label: native-only input admitted ${unwanted.take(4).joinToString { decision ->
                "hop=${decision.hop} class=${decision.hopClass} incumbent=${decision.incumbent} " +
                    "display=${decision.display} saved=${decision.saved}"
            }}; sounding=${sounding.current().toList()}", unwanted.isEmpty())
        }

        override fun close() {
            analyser.detach()
            native.close()
        }
    }

    private data class Completion(val generation: Int, val frame: Long)
    private data class Rendered(val samples: FloatArray, val completions: List<Completion>)

    private class NativeDriver(rate: Int, voice: Int) : AutoCloseable {
        private val executable = File(requireNotNull(System.getProperty("ticktock.nativeFixtureDriver")) {
            "native fixture driver property missing; run the mandatory buildNativeFixtures prerequisite"
        }).also { require(it.isFile && it.canExecute()) { "native fixture driver missing or not executable" } }
        private val process = ProcessBuilder(executable.absolutePath, rate.toString(), MIDI.toString(), voice.toString(), A4.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val watchdog = Timer("native-fixture-deadline", true).apply {
            schedule(object : TimerTask() { override fun run() { process.destroyForcibly() } }, 60_000)
        }
        private val input = DataInputStream(BufferedInputStream(process.inputStream))
        private val output = DataOutputStream(BufferedOutputStream(process.outputStream))
        var position = 0L
            private set

        init { check(input.readInt() == MAGIC) { "invalid native fixture greeting" } }

        fun duck(gain: Float, generation: Int) {
            output.writeInt(2)
            output.writeFloat(gain)
            output.writeInt(generation)
            output.flush()
        }

        fun render(frames: Int): Rendered {
            require(frames in 1..8192)
            output.writeInt(1)
            output.writeInt(frames)
            output.flush()
            check(input.readInt() == MAGIC) { "invalid native fixture response" }
            check(input.readInt() == frames) { "native fixture frame count differs" }
            val count = input.readInt()
            check(count in 0..256) { "invalid native completion count" }
            val completions = List(count) { Completion(input.readInt(), input.readLong()) }
            val samples = FloatArray(frames) { input.readFloat().also { check(it.isFinite()) } }
            check(completions.all { it.frame in position until position + frames }) { "completion outside rendered frames" }
            position += frames
            return Rendered(samples, completions)
        }

        override fun close() {
            try {
                if (process.isAlive) {
                    output.writeInt(3)
                    output.flush()
                    check(process.waitFor(3, TimeUnit.SECONDS)) { "native fixture process did not exit" }
                }
                check(process.exitValue() == 0) { "native fixture process exited ${process.exitValue()}" }
            } finally {
                watchdog.cancel()
                if (process.isAlive) process.destroyForcibly()
                output.close()
                input.close()
            }
        }
    }

    companion object {
        private val RATES = intArrayOf(44_100, 48_000)
        private const val MIDI = 62
        private const val A4 = 442
        private const val MAGIC = 0x54414631
    }
}
