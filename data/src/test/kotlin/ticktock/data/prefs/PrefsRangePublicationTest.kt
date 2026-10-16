// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.prefs

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ticktock.core.config.HornProfile
import ticktock.core.detect.ResponseMode
import ticktock.core.json.JsonValue
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import ticktock.core.prefs.Theme
import ticktock.core.ranges.UserRangeOverride
import ticktock.core.ranges.WrittenRange
import ticktock.data.StorageThread
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PrefsRangePublicationTest {
    private val storage = StorageThread()
    private val wide = UserRangeOverride(WrittenRange(45, 101))
    private val alto = Prefs(instrumentKey = "eb_alto")
    private val disk = object : PrefsFile {
        var text: String? = PrefsSchema.write(alto)
        var refused = false
        override fun read(): String? = if (refused) throw IOException("read refused") else text
        override fun write(text: String) { this.text = text }
        override fun copyAside(name: String) = Unit
    }
    private fun store() = PrefsStore(disk, storage, retryMillis = 60_000, writeDelayMillis = 60_000)
    private fun widened(prefs: Prefs) = prefs.copy(personalConfig = prefs.personalConfig.copy(
        rangeOverrides = prefs.personalConfig.rangeOverrides + ("eb_alto" to wide)))

    @After fun close() { storage.shutdown() }

    @Test fun `selected range round trip retains both source changes when final prefs are identical`() {
        val store = store().also { it.load() }
        val initial = store.publication.value
        val events = mutableListOf<PrefsPublication>()
        store.addPublicationListener { events += it }
        store.update(::widened)
        val away = store.publication.value
        store.update { initial.prefs }
        val returned = store.publication.value
        assertEquals(initial.prefs, returned.prefs)
        assertEquals(initial.rangeRevision + 1, away.rangeRevision)
        assertEquals(initial.rangeRevision + 2, returned.rangeRevision)
        assertEquals(initial.instrumentRevision, returned.instrumentRevision)
        assertEquals(listOf(initial, away, returned), events)
        store.update { it.copy(personalConfig = it.personalConfig.copy()) }
        assertEquals(returned, store.publication.value)
    }

    @Test fun `unrelated settings and unselected configuration preserve the selected range epoch`() {
        val store = store().also { it.load() }
        val initial = store.publication.value
        val changes: List<(Prefs) -> Prefs> = listOf(
            { it.copy(theme = Theme.DARK) },
            { it.copy(a4Hz = 442.0) },
            { it.copy(responseMode = ResponseMode.FAST) },
            { it.copy(hornNickname = "Practice horn") },
            { it.copy(personalConfig = it.personalConfig.copy(hornProfiles = listOf(HornProfile("alto", "eb_alto", "Practice horn")))) },
            { it.copy(personalConfig = it.personalConfig.copy(rangeOverrides = mapOf("bb_tenor" to wide))) },
            { it.copy(personalConfig = it.personalConfig.copy(unknownFields = mapOf("future" to JsonValue.JsonBool(true)))) },
        )
        changes.forEachIndexed { index, change ->
            store.update(change)
            val published = store.publication.value
            assertEquals("unrelated change $index", initial.rangeRevision, published.rangeRevision)
            assertEquals(initial.instrumentRevision, published.instrumentRevision)
            assertEquals(initial.revision + index + 1, published.revision)
        }
    }

    @Test fun `loaded selected range is published coherently before its first listener`() {
        val saved = widened(alto)
        disk.text = PrefsSchema.write(saved)
        val store = store()
        val before = store.publication.value
        assertTrue(store.load() is PrefsLoad.Loaded)
        val loaded = store.publication.value
        assertEquals(saved, loaded.prefs)
        assertEquals(before.rangeRevision + 1, loaded.rangeRevision)
        val events = mutableListOf<PrefsPublication>()
        store.addPublicationListener { events += it }
        assertEquals(listOf(loaded), events)
        store.update { it.copy(theme = Theme.PIT) }
        assertEquals(loaded.rangeRevision, store.publication.value.rangeRevision)
    }

    @Test fun `recovery advances from the visible range to the restored selected override`() {
        disk.text = PrefsSchema.write(widened(alto))
        disk.refused = true
        val store = store()
        assertTrue(store.load() is PrefsLoad.Unreadable)
        store.update { it.copy(instrumentKey = "eb_alto", a4Hz = 442.0) }
        val visible = store.publication.value
        assertTrue(store.flushAndWait().isFailure)
        disk.refused = false
        assertTrue(store.flushAndWait().isSuccess)
        val recovered = store.publication.value
        assertEquals(visible.rangeRevision + 1, recovered.rangeRevision)
        assertEquals(visible.instrumentRevision, recovered.instrumentRevision)
        assertEquals(wide, recovered.prefs.personalConfig.rangeOverrides["eb_alto"])
        assertEquals(442.0, recovered.prefs.a4Hz, 0.0)
    }

    @Test fun `replayed selected override preserves the visible epoch during recovery`() {
        disk.refused = true
        val store = store()
        assertTrue(store.load() is PrefsLoad.Unreadable)
        store.update { widened(it.copy(instrumentKey = "eb_alto")) }
        val visible = store.publication.value
        assertTrue(store.flushAndWait().isFailure)
        disk.refused = false
        assertTrue(store.flushAndWait().isSuccess)
        val recovered = store.publication.value
        assertEquals(visible.rangeRevision, recovered.rangeRevision)
        assertEquals(visible.instrumentRevision, recovered.instrumentRevision)
        assertEquals(wide, recovered.prefs.personalConfig.rangeOverrides["eb_alto"])
    }
}
