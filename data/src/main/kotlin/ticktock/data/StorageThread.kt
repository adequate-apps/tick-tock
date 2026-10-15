// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The one thread that touches storage (PLAN 3.2). History flushes, retention,
 * session edits and prefs writes all run here in order, so there is one
 * writer and no locking between them (SES-11). Callers on other threads post
 * work and never wait on disk, except where a test or the background flush
 * asks to with [call].
 */
class StorageThread(name: String = THREAD_NAME) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    @Volatile
    private var thread: Thread? = null

    init {
        executor.execute { thread = Thread.currentThread() }
    }

    /** For suspend callers: `withContext(storage.dispatcher) { ... }`. */
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    val isCurrent: Boolean get() = Thread.currentThread() === thread

    fun post(block: () -> Unit) = executor.execute(block)

    /** Runs [block] on the storage thread and waits for its result; on the storage thread itself it runs inline. */
    fun <T> call(block: () -> T): T = if (isCurrent) block() else executor.submit(Callable(block)).get()

    fun later(delayMillis: Long, block: () -> Unit): ScheduledFuture<*> =
        executor.schedule(block, delayMillis, TimeUnit.MILLISECONDS)

    /**
     * Runs [block] every [periodMillis]. A throw from one run is logged and the
     * schedule goes on: the executor would otherwise cancel a repeating task
     * after its first failure, silently, and the history would stop flushing.
     */
    fun every(periodMillis: Long, initialDelayMillis: Long = periodMillis, block: () -> Unit): ScheduledFuture<*> =
        executor.scheduleWithFixedDelay(
            {
                try {
                    block()
                } catch (failure: Throwable) {
                    Log.e(THREAD_NAME, "a scheduled storage task failed", failure)
                }
            },
            initialDelayMillis,
            periodMillis,
            TimeUnit.MILLISECONDS,
        )

    /** Lets queued work finish, then stops the thread. */
    fun shutdown(timeoutMillis: Long = 2_000) {
        executor.shutdown()
        executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    companion object {
        const val THREAD_NAME = "storage"
    }
}
