// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.crash

import ticktock.data.Storage
import java.io.File

/**
 * The fatal-error log (ERR-02). An uncaught exception is appended with the
 * time, the thread, the stack trace and the horn nickname, and nothing else:
 * no audio and no other personal data. The file is capped at
 * [Storage.CRASH_LOG_MAX_BYTES], keeping the newest entries, so a crash loop
 * cannot fill the disk. The next launch offers the log once: [pendingOffer]
 * returns it until [markOffered].
 */
class CrashLog(
    private val dir: File,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Int = Storage.CRASH_LOG_MAX_BYTES,
) {
    private val log = File(dir, Storage.CRASH_LOG_FILE)
    private val pending = File(dir, PENDING_MARKER)

    /** Runs on the crashing thread, so it does plain blocking file work and nothing that can throw past it. */
    @Synchronized
    fun record(thread: Thread, error: Throwable, nickname: String) {
        try {
            dir.mkdirs()
            val entry = buildString {
                append(ENTRY_START).append(wallClock()).append(" thread=").append(thread.name)
                if (nickname.isNotEmpty()) append(" nickname=").append(nickname)
                append('\n').append(error.stackTraceToString()).append('\n')
            }
            val kept = (if (log.isFile) log.readText() else "") + entry
            log.writeText(capped(kept))
            pending.writeText("")
        } catch (ignored: Throwable) {
            // The process is already dying; a log that cannot be written, even for want
            // of memory, must not hide the original crash or keep the platform from ending it.
        }
    }

    /** Keeps whole entries from the end until the text fits. */
    private fun capped(text: String): String {
        if (text.encodeToByteArray().size <= maxBytes) return text
        var start = 0
        while (true) {
            val next = text.indexOf(ENTRY_START, start + 1)
            if (next < 0) return text.takeLast(maxBytes / 2) // one entry larger than the cap: keep its tail
            start = next
            if (text.substring(start).encodeToByteArray().size <= maxBytes) return text.substring(start)
        }
    }

    /** The log, if a crash was recorded since it was last offered. */
    fun pendingOffer(): String? = if (pending.isFile && log.isFile) log.readText() else null

    fun markOffered() {
        pending.delete()
    }

    /** Chains a handler in front of the platform's, so the app still dies the normal way after recording. */
    fun install(nickname: () -> String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val name = try {
                    nickname()
                } catch (ignored: Throwable) {
                    ""
                }
                record(thread, error, name)
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    companion object {
        const val ENTRY_START = "=== crash at "
        private const val PENDING_MARKER = "crash.pending"
    }
}
