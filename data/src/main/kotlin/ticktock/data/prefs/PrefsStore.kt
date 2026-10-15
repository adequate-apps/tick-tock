// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.prefs

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import ticktock.core.prefs.ReadResult
import ticktock.data.StorageThread
import java.util.concurrent.ScheduledFuture

/** How the last load went. */
sealed class PrefsLoad {
    /** No prefs saved yet: a genuine first run. */
    data object Fresh : PrefsLoad()

    /** Read and normalised; [notes] name any field that had to be corrected. */
    data class Loaded(val notes: List<String>) : PrefsLoad()

    /** The file was not a prefs blob; its bytes were kept as [copy] before any write (ERR-04). */
    data class Corrupt(val reason: String, val copy: String?) : PrefsLoad()

    /** Storage could not be read at all (ERR-05). */
    data class Unreadable(val error: String) : PrefsLoad()
}

/** Something the player is told once, in a pane notice. */
enum class PrefsNotice { CORRUPT_COPY_KEPT, UNREADABLE, SAVE_FAILING }

/**
 * The one prefs writer (SES-11). Screens change prefs through [update], which
 * applies the change to the latest value atomically, so two screens saving at
 * once never lose each other's field. The file is the field table's JSON
 * through [PrefsFile], written on the storage thread shortly after a change
 * and at once by [flushAndWait] when the app goes to the background.
 *
 * A blob that cannot be parsed is copied aside before the first write and the
 * player is told once (ERR-04). Storage that cannot be read is reported and
 * never treated as a first run (ERR-05). A failed save returns its error to
 * the caller and retries on a timer until it lands (ERR-06).
 */
class PrefsStore(
    private val file: PrefsFile,
    private val storage: StorageThread,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val retryMillis: Long = RETRY_MILLIS,
    private val writeDelayMillis: Long = WRITE_DELAY_MILLIS,
) {
    private val prefsFlow = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = prefsFlow

    private val noticeFlow = MutableStateFlow<PrefsNotice?>(null)
    val notice: StateFlow<PrefsNotice?> = noticeFlow

    @Volatile
    var lastLoad: PrefsLoad? = null
        private set

    /** Saves that failed, for Diagnostics. */
    @Volatile
    var failedSaves: Long = 0
        private set

    // Storage-thread state.
    private var written: Prefs? = null
    private var scheduled: ScheduledFuture<*>? = null

    /** Reads the file and publishes the result. Call once at start, before anything reads [prefs] for real. */
    fun load(): PrefsLoad = storage.call {
        val result = try {
            when (val text = file.read()) {
                null -> PrefsLoad.Fresh.also { written = null }
                else -> when (val read = PrefsSchema.read(text)) {
                    is ReadResult.Ok -> {
                        prefsFlow.value = read.result.prefs
                        written = read.result.prefs
                        PrefsLoad.Loaded(read.result.notes)
                    }
                    is ReadResult.Corrupt -> {
                        val copy = keepCorruptCopy()
                        // The player had prefs, so this is not a first run (ERR-05 applies the same rule).
                        prefsFlow.value = Prefs(firstRunComplete = true)
                        written = null
                        noticeFlow.value = PrefsNotice.CORRUPT_COPY_KEPT
                        PrefsLoad.Corrupt(read.reason, copy)
                    }
                }
            }
        } catch (failure: Exception) {
            Log.w(TAG, "prefs could not be read", failure)
            prefsFlow.value = Prefs(firstRunComplete = true)
            // Nothing is written until the player changes something: the file may be fine once storage recovers.
            written = prefsFlow.value
            noticeFlow.value = PrefsNotice.UNREADABLE
            PrefsLoad.Unreadable(failure.toString())
        }
        lastLoad = result
        result
    }

    private fun keepCorruptCopy(): String? {
        val name = "prefs.corrupt-${wallClock()}.json"
        return try {
            file.copyAside(name)
            name
        } catch (failure: Exception) {
            // Without the copy the bytes are still on disk, untouched until the next write.
            Log.w(TAG, "could not keep a copy of the corrupt prefs", failure)
            null
        }
    }

    /** Applies [change] to the latest prefs and schedules a write. Safe from any thread. */
    fun update(change: (Prefs) -> Prefs) {
        prefsFlow.update(change)
        storage.post { scheduleWrite(writeDelayMillis) }
    }

    /** Writes now if anything changed. The error reaches the caller, and the retry path keeps running (ERR-06). */
    suspend fun save(): Result<Unit> = withContext(storage.dispatcher) { writeIfChanged() }

    /** For the lifecycle owner on background: write now without waiting. */
    fun flushSoon() = storage.post { writeIfChanged() }

    /** For the lifecycle owner on background: write now and wait. */
    fun flushAndWait(): Result<Unit> = storage.call { writeIfChanged() }

    fun acknowledgeNotice() {
        noticeFlow.value = null
    }

    /** Storage thread only. */
    private fun scheduleWrite(delayMillis: Long) {
        if (scheduled?.isDone == false) return
        scheduled = storage.later(delayMillis) { writeIfChanged() }
    }

    /** Storage thread only. */
    private fun writeIfChanged(): Result<Unit> {
        val current = prefsFlow.value
        if (current == written) return Result.success(Unit)
        return try {
            file.write(PrefsSchema.write(current))
            written = current
            if (noticeFlow.value == PrefsNotice.SAVE_FAILING) noticeFlow.value = null
            Result.success(Unit)
        } catch (failure: Exception) {
            failedSaves++
            Log.w(TAG, "prefs save failed; retrying in $retryMillis ms", failure)
            noticeFlow.value = PrefsNotice.SAVE_FAILING
            scheduled?.cancel(false)
            scheduled = storage.later(retryMillis) { writeIfChanged() }
            Result.failure(failure)
        }
    }

    companion object {
        /** Coalesces a burst of changes, such as a dragged slider, into one write. */
        const val WRITE_DELAY_MILLIS = 500L
        const val RETRY_MILLIS = 5_000L
        private const val TAG = "PrefsStore"
    }
}
