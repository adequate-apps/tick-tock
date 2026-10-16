// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.contracts.fakes.FakeSoundingNotes
import ticktock.core.detect.A4Generation
import ticktock.core.detect.Decision
import ticktock.core.detect.Detector
import ticktock.core.detect.Hop
import ticktock.core.detect.HopAnalyser
import ticktock.core.detect.YinEstimator
import ticktock.data.StorageThread
import ticktock.data.db.HistoryDatabase
import ticktock.data.history.HistoryStore
import ticktock.data.history.RunContext
import ticktock.data.history.RunKind
import ticktock.data.history.Scope
import ticktock.engine.input.AnalysisThread

/** Replays production-rendered output mixed with independent player PCM on Android. */
@RunWith(AndroidJUnit4::class)
class NativeAudioReplayTest {
    @Test
    fun nativeCorpusReplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val assets = instrumentation.context.assets
        val cases = assets.list("native-audio")?.toSet().orEmpty()
        val required = buildSet {
            for (rate in listOf(44100, 48000)) {
                for (voice in 0..2) for (scenario in listOf("pol24-leak", "pol27-root", "pol28-delayed")) {
                    add("$scenario-r$rate-v$voice")
                }
                add("pol27-lower-r$rate-v2")
            }
        }
        assertEquals("complete native corpus required", required, cases)
        val rows = mutableListOf("case,saved,stored,commands,acknowledgements,worst_cents")
        val failures = mutableListOf<String>()
        for (id in cases.sorted()) {
            try {
                val manifest = JSONObject(assets.open("native-audio/$id/manifest.json").bufferedReader().use { it.readText() })
                assertEquals(1, manifest.getInt("schemaVersion"))
                assertEquals(id, manifest.getString("caseId"))
                assertEquals("passed", manifest.getString("status"))
                val source = manifest.getJSONObject("source")
                assertEquals("corpus commit", args.getString("sourceCommit"), source.getString("commit"))
                assertEquals("corpus source snapshot", args.getString("sourceSnapshotSha256"), source.getString("snapshotSha256"))
                val audio = manifest.getJSONObject("audio")
                assertEquals("input.f32le", audio.getString("file"))
                assertEquals(1, audio.getInt("channels"))
                val bytes = assets.open("native-audio/$id/input.f32le").use { it.readBytes() }
                assertEquals(audio.getInt("sampleCount") * 4, bytes.size)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }
                assertEquals("PCM checksum", audio.getString("sha256"), hash)
                rows += replay(id, manifest, bytes)
            } catch (failure: Exception) {
                failures += "$id: $failure"
            } catch (failure: AssertionError) {
                failures += "$id: $failure"
            }
        }
        val dir = AudioEvidence.directory()
        File(dir, "native-replay.csv").writeText(rows.joinToString("\n") + "\n")
        File(dir, "native-failures.txt").writeText(failures.joinToString("\n"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals(20, rows.size - 1)
    }

    private fun replay(id: String, manifest: JSONObject, bytes: ByteArray): String {
        val audio = manifest.getJSONObject("audio")
        val scenario = manifest.getJSONObject("scenario")
        val expected = manifest.getJSONObject("expected")
        val rate = audio.getInt("sampleRate")
        val frames = audio.getInt("hopFrames")
        assertEquals(Hop.frames(rate), frames)
        assertEquals(0, audio.getInt("sampleCount") % frames)
        val hops = audio.getInt("sampleCount") / frames
        val notes = scenario.getJSONArray("soundingMidi")
        val detector = Detector(a4 = A4Generation(scenario.getDouble("a4Hz")),
            sounding = FakeSoundingNotes(*IntArray(notes.length()) { notes.getInt(it) }))
        val analyser = HopAnalyser(detector, YinEstimator(detector))
        val input = FakeAudioInput(rate, bufferFrames = scenario.getInt("inputBufferFrames"))
        val queue = LinkedBlockingQueue<List<Decision>>()
        val worker = AnalysisThread(analyser,
            promote = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }, onDecisions = queue::add)
        val commands = manifest.getJSONArray("duckCommands")
        val completions = manifest.getJSONArray("nativeCompletions")
        val acknowledgements = manifest.getJSONArray("acknowledgements")
        val pending = mutableMapOf<Int, () -> Unit>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = StorageThread()
        val database = HistoryDatabase(context, null)
        val history = HistoryStore(database, storage,
            RunContext("c_flute", 0, kind = RunKind.DRONE, droneMidi = scenario.getInt("rootMidi")))
        var saved = 0
        var worst = 0.0
        var commandIndex = 0
        var ackIndex = 0
        var firstAck = Int.MAX_VALUE
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            history.start()
            worker.start(input).getOrThrow()
            repeat(hops) { index ->
                assertTrue("unexpected prior decisions", queue.isEmpty())
                assertTrue(input.push(FloatArray(frames) { pcm.float.also { value -> assertTrue(value.isFinite() && abs(value) < 1f) } }))
                val batch = queue.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("$id hop $index timed out")
                assertEquals("one input hop per batch", 1, batch.size)
                val decision = batch.single()
                assertEquals(index.toLong(), decision.hop)
                decision.duck?.let { gain ->
                    assertTrue("extra duck request at $index", commandIndex < commands.length())
                    val wanted = commands.getJSONObject(commandIndex++)
                    assertEquals(wanted.getInt("deliveryIndex"), index)
                    assertEquals(wanted.getLong("decisionHop"), decision.hop)
                    assertEquals(wanted.getInt("gainBits"), gain.toRawBits())
                    val generation = wanted.getInt("generation")
                    assertEquals(commandIndex, generation)
                    assertNull(pending.put(generation, analyser.deferBleedEvidence()))
                }
                if (commandIndex < commands.length()) {
                    assertTrue("missing duck at hop $index", commands.getJSONObject(commandIndex).getInt("deliveryIndex") > index)
                }
                if (expected.getBoolean("noDisplayedNote")) {
                    assertNull("native leakage displayed at hop $index", decision.incumbent)
                    assertNull(decision.display)
                }
                decision.saved?.let { reading ->
                    assertTrue("saved before native duck completion", index > firstAck)
                    assertTrue("unexpected saved leakage", !expected.isNull("savedMidi"))
                    assertEquals(expected.getInt("savedMidi"), reading.midiSounding)
                    assertEquals(scenario.getDouble("a4Hz"), reading.a4Hz, 0.0)
                    val error = abs(1200.0 * ln(reading.hz / expected.getDouble("targetHz")) / ln(2.0))
                    assertTrue("$id hop $index: $error cents", error <= expected.getDouble("maxAbsCents"))
                    worst = maxOf(worst, error)
                    saved++
                    history.accept(reading, decision.firstSettled)
                }
                while (ackIndex < acknowledgements.length() && acknowledgements.getJSONObject(ackIndex).getInt("afterDeliveryIndex") == index) {
                    val ack = acknowledgements.getJSONObject(ackIndex++)
                    val generation = ack.getInt("generation")
                    val completion = (0 until completions.length()).map { completions.getJSONObject(it) }
                        .single { it.getInt("generation") == generation }
                    val delivery = completion.getInt("deliveryIndex")
                    assertEquals(delivery + scenario.getInt("acknowledgementDelayHops"), index)
                    assertTrue(completion.getLong("renderedFrame") in (delivery.toLong() * frames) until ((delivery + 1L) * frames))
                    val release = pending.remove(generation) ?: throw AssertionError("acknowledgement without a real request")
                    release()
                    firstAck = minOf(firstAck, index)
                }
            }
            assertEquals(commands.length(), commandIndex)
            assertEquals(acknowledgements.length(), ackIndex)
            assertTrue("unacknowledged output requests", pending.isEmpty())
            assertEquals(0, pcm.remaining())
            assertEquals(hops.toLong(), worker.counters.hopsAnalysed)
            assertEquals(0L, worker.counters.droppedHops)
            assertEquals(0L, worker.counters.analysisErrors)
            assertTrue(saved >= expected.getInt("minSavedReadings"))
            if (!expected.isNull("maxSavedReadings")) assertTrue(saved <= expected.getInt("maxSavedReadings"))
            val summaries = runBlocking { history.flushAndWait(); history.summaries(Scope.CurrentSession) }
            assertEquals("Android SQLite retains exactly accepted readings", saved.toLong(), summaries.sumOf { it.n })
            for (summary in summaries) {
                assertEquals(expected.getInt("savedMidi"), summary.midiSounding)
                assertEquals(scenario.getDouble("a4Hz"), summary.label.a4Hz, 0.0)
                assertTrue(abs(summary.meanCents) <= expected.getDouble("maxAbsCents"))
            }
            return "$id,$saved,${summaries.sumOf { it.n }},$commandIndex,$ackIndex,$worst"
        } finally {
            worker.stop()
            history.stop()
            storage.shutdown()
            database.close()
        }
    }
}
