// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.prefs

import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.core.contracts.Voice
import ticktock.core.prefs.PrefsSchema
import ticktock.core.prefs.ReadResult
import ticktock.core.prefs.Theme
import ticktock.data.Storage
import ticktock.data.StorageThread
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
class PrefsStoreTest {
    private val dir: File = File(RuntimeEnvironment.getApplication().filesDir, "prefs-test").apply { deleteRecursively(); mkdirs() }
    private val path = File(dir, Storage.PREFS_FILE)
    private val disk = FaultyFile(AtomicPrefsFile(path), dir)
    private val storage = StorageThread("storage-test")

    private fun store() = PrefsStore(disk, storage, wallClock = { 1_700_000_000_000L }, retryMillis = 50, writeDelayMillis = 10)

    @After
    fun tearDown() {
        storage.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun `a missing file is a first run`() {
        val store = store()
        assertEquals(PrefsLoad.Fresh, store.load())
        assertFalse(store.prefs.value.firstRunComplete)
    }

    @Test
    fun `SES-01 saved prefs come back on a cold start`() {
        val first = store()
        first.load()
        first.update { it.copy(a4Hz = 442.0, hornNickname = "Horn B") }
        assertTrue(first.flushAndWait().isSuccess)

        val cold = store()
        assertTrue(cold.load() is PrefsLoad.Loaded)
        assertEquals(442.0, cold.prefs.value.a4Hz, 0.0)
        assertEquals("Horn B", cold.prefs.value.hornNickname)
    }

    @Test
    fun `SES-11 two writers at once lose no field`() {
        val store = store()
        store.load()
        val go = CountDownLatch(1)
        val a = thread { go.await(); for (bpm in 40..240) store.update { it.copy(metroBpm = bpm) } }
        val b = thread { go.await(); for (offset in -100..100) store.update { it.copy(visualOffsetMs = offset) } }
        go.countDown()
        a.join()
        b.join()
        assertTrue(store.flushAndWait().isSuccess)

        val reloaded = store().also { it.load() }.prefs.value
        assertEquals(240, reloaded.metroBpm)
        assertEquals(100, reloaded.visualOffsetMs)
    }

    @Test
    fun `SES-12 unknown and newer fields survive a load and a save`() {
        path.writeText("""{"schemaVersion": 9, "a4Hz": 441, "futureThing": {"x": [1, 2]}, "anotherNew": "kept"}""")
        val store = store()
        store.load()
        store.update { it.copy(metroBpm = 77) }
        assertTrue(store.flushAndWait().isSuccess)

        val text = path.readText()
        assertTrue(text, text.contains("\"futureThing\""))
        assertTrue(text, text.contains("\"anotherNew\": \"kept\""))
        assertTrue(text, text.contains("\"metroBpm\": 77"))
    }

    @Test
    fun `SES-13 a drone volume of zero, the voice and the note come back exactly`() {
        val first = store()
        first.load()
        first.update { it.copy(droneGain = 0f, droneVoice = Voice.ROOT_FIFTH, droneMidi = 57) }
        first.flushAndWait()

        val back = store().also { it.load() }.prefs.value
        assertEquals(0f, back.droneGain)
        assertEquals(Voice.ROOT_FIFTH, back.droneVoice)
        assertEquals(57, back.droneMidi)
    }

    @Test
    fun `ERR-04 a corrupt blob is copied before the first write and the player is told once`() {
        val garbage = "{ this is not json"
        path.writeText(garbage)
        val store = store()
        val load = store.load()

        assertTrue(load is PrefsLoad.Corrupt)
        val copy = File(dir, (load as PrefsLoad.Corrupt).copy!!)
        assertEquals(garbage, copy.readText())
        assertEquals(PrefsNotice.CORRUPT_COPY_KEPT, store.notice.value)
        assertTrue("a player with prefs is not a new user", store.prefs.value.firstRunComplete)

        store.update { it.copy(metroBpm = 90) }
        store.flushAndWait()
        assertEquals(garbage, copy.readText())
        store.acknowledgeNotice()
        assertNull(store.notice.value)
        assertTrue(store().load() is PrefsLoad.Loaded)
    }

    @Test
    fun `ERR-05 unreadable storage is reported and never greeted as a new user`() {
        path.writeText(PrefsSchema.write(ticktock.core.prefs.Prefs(firstRunComplete = true, metroBpm = 66)))
        disk.failReads = true
        val store = store()
        assertTrue(store.load() is PrefsLoad.Unreadable)
        assertEquals(PrefsNotice.UNREADABLE, store.notice.value)
        assertTrue(store.prefs.value.firstRunComplete)

        assertTrue("nothing is written over the file until the player changes something", store.flushAndWait().isSuccess)
        disk.failReads = false
        assertEquals(66, store().also { it.load() }.prefs.value.metroBpm)
    }

    @Test
    fun `ERR-05 a change made while storage is unreadable lands on the saved prefs, never over them`() {
        path.writeText(PrefsSchema.write(ticktock.core.prefs.Prefs(firstRunComplete = true, metroBpm = 66, hornNickname = "Horn B")))
        disk.failReads = true
        val store = store()
        assertTrue(store.load() is PrefsLoad.Unreadable)
        store.update { it.copy(theme = Theme.DARK) }
        assertTrue("nothing is written while the file cannot be read", store.flushAndWait().isFailure)
        assertEquals(66, PrefsSchema.read(path.readText()).let { (it as ReadResult.Ok).result.prefs.metroBpm })

        disk.failReads = false
        val deadline = System.nanoTime() + 2_000_000_000L
        while (store.prefs.value.hornNickname != "Horn B" && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(store.flushAndWait().isSuccess)
        val back = store().also { it.load() }.prefs.value
        assertEquals("Horn B", back.hornNickname)
        assertEquals(66, back.metroBpm)
        assertEquals(Theme.DARK, back.theme)
    }

    @Test
    fun `ERR-04 when the corrupt blob cannot be copied, nothing overwrites it and the player is not told it was kept`() {
        path.writeText("{ broken")
        disk.failCopies = true
        val store = store()
        val load = store.load() as PrefsLoad.Corrupt
        assertEquals(null, load.copy)
        assertEquals(PrefsNotice.UNREADABLE, store.notice.value)
        store.update { it.copy(metroBpm = 90) }
        assertTrue(store.flushAndWait().isFailure)
        assertEquals("{ broken", path.readText())

        disk.failCopies = false
        val deadline = System.nanoTime() + 2_000_000_000L
        while (dir.listFiles().orEmpty().none { it.name.startsWith("prefs.corrupt-") } && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(store.flushAndWait().isSuccess)
        assertEquals(90, store().also { it.load() }.prefs.value.metroBpm)
        assertEquals("{ broken", dir.listFiles()!!.single { it.name.startsWith("prefs.corrupt-") }.readText())
    }

    @Test
    fun `ERR-06 a failed save reaches the caller and the retry lands it`() = runBlocking {
        val store = store()
        store.load()
        disk.failWrites = true
        store.update { it.copy(metroBpm = 123) }
        val result = store.save()
        assertTrue(result.isFailure)
        assertEquals(PrefsNotice.SAVE_FAILING, store.notice.value)

        disk.failWrites = false
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!path.isFile && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("the retry wrote the file", path.isFile)
        assertEquals(123, store().also { it.load() }.prefs.value.metroBpm)
        assertNull(store.notice.value)
    }

    /** A prefs file whose reads and writes can be made to fail. */
    private class FaultyFile(private val real: PrefsFile, private val dir: File) : PrefsFile {
        @Volatile var failReads = false
        @Volatile var failWrites = false
        @Volatile var failCopies = false

        override fun read(): String? = if (failReads) throw IOException("read failed (planted)") else real.read()

        override fun write(text: String) = if (failWrites) throw IOException("no space left (planted)") else real.write(text)

        override fun copyAside(name: String) = if (failCopies) throw IOException("copy failed (planted)") else real.copyAside(name)
    }
}
