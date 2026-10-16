// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
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
import ticktock.core.json.Json
import ticktock.core.json.JsonValue

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
    fun `POL-27 a player below the native root and fifth earns admission through the duck`() {
        for (rate in RATES) Rig(rate, 2, playerAmplitude = 0.5, playerMidi = MIDI - 12).use { rig ->
            rig.run(60)
            assertEquals(rig.label, listOf(0.30f), rig.requestedGains)
            assertTrue(rig.label, rig.firstAcknowledgedHop != Long.MAX_VALUE)
            assertTrue(rig.label, rig.decisions.takeWhile { it.hop < rig.firstAcknowledgedHop }.all { it.saved == null })
            val readings = rig.decisions.mapNotNull { it.saved }
            assertTrue("${rig.label}: the lower player must reach history", readings.size >= 10)
            assertTrue(rig.label, readings.all { it.midiSounding == MIDI - 12 && abs(it.cents) < 1.0 })
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
        private val voice: Int,
        private val playerAmplitude: Double = 0.0,
        private val playerMidi: Int = MIDI,
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
        private val export = FixtureExport.create()
        private val native = NativeDriver(rate, voice)
        private var generation = 0
        private var samplePosition = 0L
        private var hop = 0
        private val acknowledgements = mutableMapOf<Int, () -> Unit>()
        private val due = mutableListOf<PendingAcknowledgement>()
        private var validated = false
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
                val hz = A4 * 2.0.pow((playerMidi - 69) / 12.0)
                val mixed = FloatArray(hopFrames) { i ->
                    val player = playerAmplitude * sin(2.0 * PI * hz * (samplePosition + i) / rate)
                    (rendered.samples[i] + player).toFloat().also { require(abs(it) < 1f) }
                }
                samplePosition += hopFrames
                export?.samples?.add(mixed)
                assertTrue(input.push(mixed))
                val batch = analyser.drain()
                decisions += batch
                for (decision in batch) decision.duck?.let { gain ->
                    requestedGains += gain
                    generation++
                    acknowledgements[generation] = analyser.deferBleedEvidence()
                    native.duck(gain, generation)
                    export?.commands?.add(Json.obj(
                        "deliveryIndex" to Json.num(hop), "decisionHop" to Json.num(decision.hop.toDouble()),
                        "generation" to Json.num(generation), "gainBits" to Json.num(gain.toRawBits()),
                        "gain" to Json.num(gain.toDouble()),
                    ))
                }
                for (completed in rendered.completions) {
                    val acknowledge = acknowledgements.remove(completed.generation)
                        ?: error("$label: unexpected native generation ${completed.generation}")
                    due += PendingAcknowledgement(hop + acknowledgementDelayHops, completed.generation, acknowledge)
                    export?.completions?.add(Json.obj(
                        "deliveryIndex" to Json.num(hop), "generation" to Json.num(completed.generation),
                        "renderedFrame" to Json.num(completed.frame.toDouble()),
                    ))
                }
                // Simulated zero output latency: delivery occurs at the rendered block boundary.
                // HopAnalyser additionally excludes upstream capture buffering and overlapping windows.
                val ready = due.filter { it.afterDeliveryIndex <= hop }
                due.removeAll(ready.toSet())
                for (pending in ready) {
                    pending.acknowledge()
                    firstAcknowledgedHop = minOf(firstAcknowledgedHop, hop.toLong())
                    export?.acknowledgements?.add(Json.obj(
                        "afterDeliveryIndex" to Json.num(hop), "generation" to Json.num(pending.generation),
                    ))
                }
                hop++
            }
        }

        fun assertHealthy() {
            assertEquals(label, 0L, analyser.counters.droppedHops)
            assertEquals(label, 0L, analyser.counters.analysisErrors)
            assertFalse(label, decisions.isEmpty())
            validated = true
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
            if (validated) export?.write(manifest())
        }

        private fun manifest(): JsonValue.JsonObject {
            val player = playerAmplitude > 0.0
            val criterion = if (player) "POL-27" else if (acknowledgementDelayHops > 0) "POL-28" else "POL-24"
            val casePrefix = if (player) {
                if (playerMidi == MIDI) "pol27-root" else "pol27-lower"
            } else if (acknowledgementDelayHops > 0) "pol28-delayed" else "pol24-leak"
            return Json.obj(
                "schemaVersion" to Json.num(1), "caseId" to Json.str("$casePrefix-r$rate-v$voice"),
                "status" to Json.str("passed"),
                "audio" to Json.obj(
                    "file" to Json.str("input.f32le"), "sampleRate" to Json.num(rate), "channels" to Json.num(1),
                    "sampleCount" to Json.num(samplePosition.toDouble()), "hopFrames" to Json.num(hopFrames),
                ),
                "scenario" to Json.obj(
                    "criterion" to Json.str(criterion), "voice" to Json.num(voice), "rootMidi" to Json.num(MIDI),
                    "soundingMidi" to Json.arr(sounding.current().map(Json::num)), "a4Hz" to Json.num(A4),
                    "playerMidi" to if (player) Json.num(playerMidi) else JsonValue.JsonNull,
                    "playerAmplitude" to Json.num(playerAmplitude), "inputBufferFrames" to Json.num(input.bufferFrames),
                    "acknowledgementDelayHops" to Json.num(acknowledgementDelayHops),
                    "outputLatencyModel" to Json.str("zero-at-rendered-block-boundary"),
                ),
                "expected" to Json.obj(
                    "savedMidi" to if (player) Json.num(playerMidi) else JsonValue.JsonNull,
                    "targetHz" to if (player) Json.num(A4 * 2.0.pow((playerMidi - 69) / 12.0)) else JsonValue.JsonNull,
                    "maxAbsCents" to Json.num(1.0), "minSavedReadings" to Json.num(if (player) 10 else 0),
                    "maxSavedReadings" to if (player) JsonValue.JsonNull else Json.num(0),
                    "noDisplayedNote" to Json.bool(!player),
                ),
                "observed" to Json.obj(
                    "savedReadings" to Json.num(decisions.count { it.saved != null }),
                    "firstAcknowledgedHop" to if (firstAcknowledgedHop == Long.MAX_VALUE) JsonValue.JsonNull else Json.num(firstAcknowledgedHop.toDouble()),
                    "droppedHops" to Json.num(analyser.counters.droppedHops.toDouble()),
                    "analysisErrors" to Json.num(analyser.counters.analysisErrors.toDouble()),
                ),
            )
        }
    }

    private data class PendingAcknowledgement(val afterDeliveryIndex: Int, val generation: Int, val acknowledge: () -> Unit)

    /** Optional test artifact; only a fully checked case may publish its directory. */
    private class FixtureExport(private val directory: File, private val commit: String, private val snapshot: String) {
        val samples = mutableListOf<FloatArray>()
        val commands = mutableListOf<JsonValue>()
        val completions = mutableListOf<JsonValue>()
        val acknowledgements = mutableListOf<JsonValue>()

        fun write(manifest: JsonValue.JsonObject) {
            val caseId = requireNotNull(manifest["caseId"]?.string)
            val root = directory.toPath()
            Files.createDirectories(root)
            val target = root.resolve(caseId)
            check(!Files.exists(target)) { "fixture case already exists; use a fresh output directory: $caseId" }
            val temporary = Files.createTempDirectory(root, ".$caseId-")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                DataOutputStream(BufferedOutputStream(DigestOutputStream(Files.newOutputStream(temporary.resolve("input.f32le")), digest))).use { output ->
                    for (block in samples) for (sample in block) output.writeInt(Integer.reverseBytes(sample.toRawBits()))
                }
                val audio = requireNotNull(manifest["audio"]?.obj)
                check(samples.sumOf { it.size.toLong() }.toDouble() == audio["sampleCount"]?.number)
                audio["sha256"] = Json.str(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
                manifest["source"] = Json.obj("commit" to Json.str(commit), "snapshotSha256" to Json.str(snapshot))
                manifest["duckCommands"] = Json.arr(commands)
                manifest["nativeCompletions"] = Json.arr(completions)
                manifest["acknowledgements"] = Json.arr(acknowledgements)
                temporary.resolve("manifest.json").toFile().writeText(Json.write(manifest))
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                if (Files.exists(temporary)) temporary.toFile().deleteRecursively()
            }
        }

        companion object {
            fun create(): FixtureExport? {
                val output = System.getProperty("ticktock.audioFixtureOutputDir") ?: return null
                require(output.isNotBlank()) { "fixture output directory is empty" }
                val commit = requireNotNull(System.getProperty("ticktock.sourceCommit")) { "fixture source commit missing" }
                val snapshot = requireNotNull(System.getProperty("ticktock.sourceSnapshotSha256")) { "fixture source snapshot missing" }
                require(commit.matches(Regex("[0-9a-f]{40}"))) { "invalid fixture source commit" }
                require(snapshot.matches(Regex("[0-9a-f]{64}"))) { "invalid fixture source snapshot SHA-256" }
                return FixtureExport(File(output), commit, snapshot)
            }
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
