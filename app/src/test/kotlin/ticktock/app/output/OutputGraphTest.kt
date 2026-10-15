// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.app.AppGraph
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.ParkReason
import ticktock.core.detect.Reading
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import ticktock.data.Storage
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OutputGraphTest {
    @Test
    fun `CTX-02 and POL-24 a zero-volume drone leaves player attacks and persisted context free`() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "output-silent").apply { mkdirs() }
        File(dir, Storage.PREFS_FILE).writeText(PrefsSchema.write(Prefs(droneMidi = 69, droneGain = 0.5f,
            responseMode = ticktock.core.detect.ResponseMode.FAST)))
        val link = TestOutputConnection()
        val graph = AppGraph(context, dir, "output-silent.db") { link }
        val input = FakeAudioInput(sampleRate = 48_000)
        graph.tune.micAllowed = { true }
        graph.tune.makeInput = { input }
        try {
            graph.output.startDrone()
            repeat(5) { graph.output.droneVolume(-0.1f) }
            assertEquals(0f, graph.prefs.prefs.value.droneGain, 0f)
            assertTrue(graph.output.current().isEmpty())
            assertFalse(graph.output.state.value.droneSounding)
            graph.tune.resume()
            val sine = Sine(440.0, 0.2, 48_000)
            repeat(16) {
                val before = graph.tune.trace.value
                input.push(sine.next(Hop.frames(48_000)))
                val deadline = System.nanoTime() + 5_000_000_000L
                while (graph.tune.trace.value === before && System.nanoTime() < deadline) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(1)
                }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(graph.tune.trace.value !== before)
            }
            assertEquals(69, graph.tune.state.value.readout.midi)
            assertTrue(link.sent.none { it is OutputCommand.Duck && it.gain < 1f })
            graph.output.droneVolume(0.5f)
            assertEquals(listOf(69), graph.output.current().toList())
            graph.output.droneVolume(-0.5f)
            assertTrue(graph.output.current().isEmpty())
            assertFalse(graph.output.state.value.droneSounding)
            assertTrue(graph.output.state.value.droneWanted)
            graph.captureHistory.accept(Reading(20, 69, 440.0, 0.0, 440.0, 0))
            assertTrue(graph.history.flushAndWait())
            context.openOrCreateDatabase("output-silent.db", 0, null).use { db ->
                db.rawQuery("SELECT runs.context, runs.drone_midi FROM readings JOIN runs ON readings.run_id = runs.id", null).use { rows ->
                    assertTrue(rows.count > 1)
                    while (rows.moveToNext()) {
                        assertEquals("free", rows.getString(0))
                        assertTrue(rows.isNull(1))
                    }
                }
            }
        } finally {
            graph.close()
            context.deleteDatabase("output-silent.db")
            dir.deleteRecursively()
        }
    }

    @Test
    fun `CTX-02 actual sounding roots label history across preferences retune park and stop`() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "output-graph").apply { mkdirs() }
        File(dir, Storage.PREFS_FILE).writeText(PrefsSchema.write(Prefs(droneMidi = 69, a4Hz = 442.0)))
        val link = TestOutputConnection()
        val graph = AppGraph(context, dir, "output-graph.db") { link }
        try {
            var hop = 0L
            fun save() {
                graph.captureHistory.accept(Reading(hop++, 69, 442.0, 0.0, 442.0, 0))
                graph.history.flushAndWait()
            }
            save()
            graph.output.startDrone()
            save()
            graph.prefs.update { it.copy(earsFirst = true) }
            save()
            graph.output.selectMidi(72)
            save()
            // The event need not have reached the UI for the capture boundary to see silence.
            link.park(ParkReason.HEADPHONES_DISCONNECTED)
            save()
            link.replug()
            save()
            graph.output.stopDrone()
            save()
            context.openOrCreateDatabase("output-graph.db", 0, null).use { db ->
                val rows = mutableListOf<Pair<String, Int?>>()
                db.rawQuery("SELECT context, drone_midi FROM runs ORDER BY id", null).use { cursor ->
                    while (cursor.moveToNext()) rows += cursor.getString(0) to if (cursor.isNull(1)) null else cursor.getInt(1)
                }
                assertEquals(listOf("free" to null, "drone" to 69, "drone" to 72, "free" to null,
                    "drone" to 72, "free" to null), rows)
            }
            assertEquals(OutputCommand.SetA4(442.0), link.sent.first())
        } finally {
            graph.close()
            context.deleteDatabase("output-graph.db")
            dir.deleteRecursively()
        }
    }

    @Test
    fun `POL-24 and POL-27 the app owner excludes its own audio and saves a player through the duck`() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "output-capture").apply { mkdirs() }
        File(dir, Storage.PREFS_FILE).writeText(PrefsSchema.write(Prefs(droneMidi = 69)))
        val link = TestOutputConnection()
        val graph = AppGraph(context, dir, "output-capture.db") { link }
        val input = FakeAudioInput(sampleRate = 48_000)
        graph.tune.micAllowed = { true }
        graph.tune.makeInput = { input }
        try {
            graph.output.startDrone()
            graph.tune.resume()
            val sine = Sine(440.0, 0.2, 48_000)
            fun push(followsDuck: Boolean) {
                val before = graph.tune.trace.value
                val samples = sine.next(Hop.frames(48_000))
                input.push(if (followsDuck) samples.map { it * link.duckGain.get() }.toFloatArray() else samples)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (graph.tune.trace.value === before && System.nanoTime() < deadline) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(1)
                }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue("audio reached the app capture", graph.tune.trace.value !== before)
            }
            fun saved(): Long {
                assertTrue(graph.history.flushAndWait())
                return context.openOrCreateDatabase("output-capture.db", 0, null).use { db ->
                    db.rawQuery("SELECT COUNT(*) FROM readings", null).use { rows -> rows.moveToFirst(); rows.getLong(0) }
                }
            }
            repeat(35) { push(followsDuck = true) }
            assertEquals(0.3f, link.duckGain.get())
            assertEquals(0L, saved())
            repeat(35) { push(followsDuck = false) }
            assertTrue("a player survives the duck and reaches persisted history", saved() > 0)
            val beforeGainChange = saved()
            graph.output.droneVolume(0.5f)
            repeat(35) {
                val before = graph.tune.trace.value
                input.push(sine.next(Hop.frames(48_000)).map { it * 4 * link.duckGain.get() }.toFloatArray())
                val deadline = System.nanoTime() + 5_000_000_000L
                while (graph.tune.trace.value === before && System.nanoTime() < deadline) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(1)
                }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(graph.tune.trace.value !== before)
            }
            assertEquals("a changed drone needs fresh full-gain evidence", beforeGainChange, saved())
            repeat(35) {
                val before = graph.tune.trace.value
                input.push(sine.next(Hop.frames(48_000)).map { it * 4 }.toFloatArray())
                val deadline = System.nanoTime() + 5_000_000_000L
                while (graph.tune.trace.value === before && System.nanoTime() < deadline) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(1)
                }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(graph.tune.trace.value !== before)
            }
            assertTrue("a new player can earn admission against the new baseline", saved() > beforeGainChange)
            graph.onBackground()
            assertEquals(1f, link.duckGain.get())
        } finally {
            graph.close()
            context.deleteDatabase("output-capture.db")
            dir.deleteRecursively()
        }
    }

    @Test
    fun `OUT-11 graph lifecycle stops output and cold restart retains controls without starting sound`() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "output-restart").apply { mkdirs() }
        val link = TestOutputConnection()
        val graph = AppGraph(context, dir, null) { link }
        graph.tune.micAllowed = { false }
        try {
            graph.output.selectMidi(57)
            graph.output.bpm(137)
            graph.output.startDrone()
            graph.output.startMetro()
            graph.onBackground()
            graph.onForeground()
            graph.prefs.flushAndWait()
            assertTrue(link.registered.isEmpty())
        } finally { graph.close() }
        var created = false
        val restored = AppGraph(context, dir, null) { created = true; TestOutputConnection() }
        try {
            assertEquals(57, restored.output.selectedMidi())
            assertEquals(137, restored.prefs.prefs.value.metroBpm)
            assertFalse(created)
            assertFalse(restored.output.state.value.toolRunning)
        } finally { restored.close(); dir.deleteRecursively() }
    }
}
