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

    @Test
    fun `ERR-02 an oversized Unicode entry keeps a valid UTF-8 tail within its byte cap`() {
        for (cap in listOf(4_096, Storage.CRASH_LOG_MAX_BYTES, 1, 2, 3, 4, 5, 31)) {
            for (unit in listOf("\u754c", "\uD834\uDD1E", "a\u754c\uD834\uDD1E")) {
                val log = CrashLog(dir, maxBytes = cap)
                val failure = object : RuntimeException("oversized entry") {
                    override fun printStackTrace(writer: java.io.PrintWriter) {
                        writer.print(unit.repeat(cap + 4))
                    }
                }
                log.record(Thread.currentThread(), failure, nickname = "")
                val bytes = File(dir, Storage.CRASH_LOG_FILE).readBytes()
                assertTrue("${bytes.size} bytes exceed cap $cap", bytes.size <= cap)
                val tail = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                assertTrue("the newest newline fits cap $cap", tail.endsWith("\n"))
                assertTrue("the log tail belongs to the recorded entry", (unit.repeat(cap + 4) + "\n").endsWith(tail))
            }
        }
    }

    @Test
    fun `ERR-02 an error while recording still hands the crash to the platform handler`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var handedOn = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> handedOn = true }
        try {
            CrashLog(dir).install { throw OutOfMemoryError("planted while reading the nickname") }
            val poisoned = object : RuntimeException("crash") {
                override fun printStackTrace(s: java.io.PrintWriter) = throw OutOfMemoryError("planted while formatting")
            }
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), poisoned)
            assertTrue(handedOn)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
