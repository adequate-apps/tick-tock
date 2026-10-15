// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.core.catalogue.Catalogue
import ticktock.core.detect.Reading
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import ticktock.data.Storage
import ticktock.data.history.Scope
import ticktock.data.prefs.PrefsLoad
import java.io.File

/** Prefs, history and the crash log wired together the way a launch wires them. */
@RunWith(RobolectricTestRunner::class)
class AppGraphTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dir = File(context.filesDir, "graph-test").apply { deleteRecursively(); mkdirs() }
    private var graph: AppGraph? = null

    @After
    fun tearDown() {
        graph?.close()
        context.deleteDatabase(DB)
        dir.deleteRecursively()
    }

    private fun reading(hop: Long, a4: Double) = Reading(hop = hop, midiSounding = 69, hz = a4, cents = 0.0, a4Hz = a4, a4Generation = 0)

    @Test
    fun `SES-01 a cold start labels the first run with the saved prefs, and a prefs change relabels the next`() = runBlocking {
        val key = "eb_alto"
        File(dir, Storage.PREFS_FILE).writeText(PrefsSchema.write(Prefs(instrumentKey = key, a4Hz = 442.0, hornNickname = "Horn B", firstRunComplete = true)))
        val g = AppGraph(context, dir, DB).also { graph = it }
        assertTrue(g.prefsLoad is PrefsLoad.Loaded)

        g.history.accept(reading(0, 442.0))
        g.history.flushAndWait()
        val first = g.history.summaries(Scope.CurrentSession).single().label
        assertEquals("Horn B", first.nickname)
        assertEquals(key, first.instrumentKey)
        assertEquals(Catalogue.get(key).transposition, first.transposition)
        assertEquals(442.0, first.a4Hz, 0.0)

        g.prefs.update { it.copy(hornNickname = "Horn C") }
        val deadline = System.nanoTime() + 2_000_000_000L
        var labels: Set<String> = emptySet()
        var hop = 1L
        while (System.nanoTime() < deadline && "Horn C" !in labels) {
            g.history.accept(reading(hop++, 442.0))
            g.history.flushAndWait()
            labels = g.history.summaries(Scope.CurrentSession).map { it.label.nickname }.toSet()
            Thread.sleep(10)
        }
        assertEquals(setOf("Horn B", "Horn C"), labels)
    }

    @Test
    fun `the background flush writes pending prefs and readings`() = runBlocking {
        val g = AppGraph(context, dir, DB).also { graph = it }
        g.prefs.update { it.copy(metroBpm = 99) }
        g.history.accept(reading(0, 440.0))
        g.onBackground()
        g.storage.call { } // the flushes were posted ahead of this
        assertTrue(File(dir, Storage.PREFS_FILE).readText().contains("\"metroBpm\": 99"))
        assertEquals(1L, g.history.summaries(Scope.CurrentSession).single().n)
    }

    private companion object {
        const val DB = "graph-test.db"
    }
}
