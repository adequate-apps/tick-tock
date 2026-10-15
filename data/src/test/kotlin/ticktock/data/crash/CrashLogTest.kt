// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.crash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.data.Storage
import java.io.File
import java.nio.file.Files

class CrashLogTest {
    private val dir: File = Files.createTempDirectory("crashlog").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `ERR-02 a crash is offered once on the next launch`() {
        val log = CrashLog(dir, wallClock = { 42L })
        assertNull(log.pendingOffer())
        log.record(Thread.currentThread(), IllegalStateException("boom"), nickname = "Horn B")

        val nextLaunch = CrashLog(dir)
        val text = nextLaunch.pendingOffer()!!
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains("nickname=Horn B"))
        nextLaunch.markOffered()
        assertNull(CrashLog(dir).pendingOffer())
    }

    @Test
    fun `ERR-02 the log is capped and keeps the newest whole entries`() {
        val log = CrashLog(dir, maxBytes = 4_096)
        repeat(50) { log.record(Thread.currentThread(), RuntimeException("crash number $it"), nickname = "") }
        val text = File(dir, Storage.CRASH_LOG_FILE).readText()

        assertTrue(text.encodeToByteArray().size <= 4_096)
        assertTrue(text.startsWith(CrashLog.ENTRY_START))
        assertTrue(text.contains("crash number 49"))
        assertFalse(text.contains("crash number 0\n"))
    }

    @Test
    fun `ERR-02 an empty nickname writes no nickname field`() {
        val log = CrashLog(dir)
        log.record(Thread.currentThread(), RuntimeException("x"), nickname = "")
        assertFalse(log.pendingOffer()!!.contains("nickname="))
    }

    @Test
    fun `the cap is the 256 KiB the storage names promise`() {
        assertEquals(256 * 1024, Storage.CRASH_LOG_MAX_BYTES)
    }
}
