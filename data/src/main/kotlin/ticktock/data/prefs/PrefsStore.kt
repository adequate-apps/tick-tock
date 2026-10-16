// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.prefs

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import ticktock.core.catalogue.Catalogue
import ticktock.core.ranges.BaseRanges
import ticktock.core.ranges.OverlayRanges
import ticktock.core.ranges.RangeResolver
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import ticktock.core.prefs.ReadResult
import ticktock.data.StorageThread
import java.util.concurrent.ScheduledFuture

/** One visible prefs value and its process-local publication identities; revisions are never persisted. */
data class PrefsPublication(val prefs: Prefs, val revision: Long, val instrumentRevision: Long, val rangeRevision: Long = 0)

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
    private val publicationFlow = MutableStateFlow(PrefsPublication(prefsFlow.value, 0, 0))
    // Load the bundled tables before updates enter the publication monitor.
    private var selectedRange = selectedRangeOf(prefsFlow.value)

    /** Read prefs and their instrument epoch together when admitting capture readings. */
    val publication: StateFlow<PrefsPublication> = publicationFlow

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

    /**
     * Why the file must not be written yet. While storage is unreadable, or a
     * corrupt file has not been copied aside, a write could destroy the only
     * copy of the player's prefs (ERR-04, ERR-05).
     */
    private enum class Guard { NONE, UNREADABLE, CORRUPT_UNCOPIED }

    @Volatile
    private var guard = Guard.NONE

    /** Changes made while [guard] holds, replayed onto the stored prefs once they can be read. */
    private val replay = ArrayList<(Prefs) -> Prefs>()

    /** Reads the file and publishes the result. Call once at start, before anything reads [prefs] for real. */
    fun load(): PrefsLoad = storage.call {
        var loadedPublication = publicationFlow.value
        val result = try {
            when (val text = file.read()) {
                null -> PrefsLoad.Fresh.also { written = null }
                else -> when (val read = PrefsSchema.read(text)) {
                    is ReadResult.Ok -> {
                        loadedPublication = synchronized(replay) { publishLocked(read.result.prefs) }
                        written = read.result.prefs
                        PrefsLoad.Loaded(read.result.notes)
                    }
                    is ReadResult.Corrupt -> {
                        val copy = keepCorruptCopy()
                        // The player had prefs, so this is not a first run (ERR-05 applies the same rule).
                        loadedPublication = synchronized(replay) { publishLocked(Prefs(firstRunComplete = true)) }
                        written = null
                        if (copy != null) {
                            noticeFlow.value = PrefsNotice.CORRUPT_COPY_KEPT
                        } else {
                            // No copy, no write: the corrupt bytes stay the only record until one is kept.
                            guard = Guard.CORRUPT_UNCOPIED
                            noticeFlow.value = PrefsNotice.UNREADABLE
                        }
                        PrefsLoad.Corrupt(read.reason, copy)
                    }
                }
            }
        } catch (failure: Exception) {
            Log.w(TAG, "prefs could not be read", failure)
            loadedPublication = synchronized(replay) { publishLocked(Prefs(firstRunComplete = true)) }
            // Nothing is written until the file has been read: it may be fine once storage recovers.
            written = loadedPublication.prefs
            guard = Guard.UNREADABLE
            noticeFlow.value = PrefsNotice.UNREADABLE
            PrefsLoad.Unreadable(failure.toString())
        }
        lastLoad = result
        notifyPublication(loadedPublication)
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
        val published = synchronized(replay) {
            val next = change(publicationFlow.value.prefs)
            if (guard != Guard.NONE) replay += change
            publishLocked(next)
        }
        notifyPublication(published)
        storage.post { scheduleWrite(writeDelayMillis) }
    }

    /** Caller holds replay's monitor. Publish one coherent identity before the compatibility prefs flow. */
    private fun publishLocked(next: Prefs): PrefsPublication {
        val previous = publicationFlow.value
        if (next == previous.prefs) return previous
        val nextRange = selectedRangeOf(next)
        val published = PrefsPublication(next, previous.revision + 1,
            previous.instrumentRevision + if (next.instrumentKey == previous.prefs.instrumentKey) 0 else 1,
            previous.rangeRevision + if (nextRange == selectedRange) 0 else 1)
        selectedRange = nextRange
        publicationFlow.value = published
        prefsFlow.value = next
        return published
    }

    private fun selectedRangeOf(prefs: Prefs) = RangeResolver.resolve(
        Catalogue.get(prefs.instrumentKey), BaseRanges.bundled, OverlayRanges.bundled,
        prefs.personalConfig.rangeOverrides[prefs.instrumentKey])

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(PrefsPublication) -> Unit>()

    /** Call outside every prefs lock; concurrent publishers may deliver captured events out of order. */
    private fun notifyPublication(published: PrefsPublication) {
        listeners.forEach { it(published) }
    }

    /** Compatibility listener for consumers that do not track publication identity. */
    fun addListener(listener: (Prefs) -> Unit) = addPublicationListener { listener(it.prefs) }

    /**
     * Supplies one immutable publication now and after each update. Callbacks run on the publishing
     * thread outside prefs locks. Consumers that retain state reject revisions older than their own;
     * instrument and range round trips remain visible even if a newer callback overtakes an older one.
     */
    fun addPublicationListener(listener: (PrefsPublication) -> Unit) {
        val current = synchronized(replay) {
            listeners += listener
            publicationFlow.value
        }
        listener(current)
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
        // Nothing changed since the load: nothing to write, guarded or not.
        if (publicationFlow.value.prefs == written && synchronized(replay) { replay.isEmpty() }) return Result.success(Unit)
        if (!safeToWrite()) {
            scheduled?.cancel(false)
            scheduled = storage.later(retryMillis) { writeIfChanged() }
            return Result.failure(java.io.IOException("the prefs file cannot be read, so it is not written"))
        }
        val current = publicationFlow.value.prefs
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

    /** Storage thread only. Clears the [guard] when its cause has gone, merging what was stored. */
    private fun safeToWrite(): Boolean {
        if (guard == Guard.UNREADABLE) {
            val text = try {
                file.read()
            } catch (failure: Exception) {
                Log.w(TAG, "prefs still unreadable", failure)
                return false
            }
            val stored = text?.let(PrefsSchema::read)
            if (stored is ReadResult.Corrupt) {
                guard = Guard.CORRUPT_UNCOPIED
            } else {
                val base = (stored as? ReadResult.Ok)?.result?.prefs
                val recovered = synchronized(replay) {
                    val merged = if (base == null) publicationFlow.value.prefs
                        else replay.fold(base) { prefs, change -> change(prefs) }
                    val published = publishLocked(merged)
                    replay.clear()
                    guard = Guard.NONE
                    published
                }
                written = base
                if (noticeFlow.value == PrefsNotice.UNREADABLE) noticeFlow.value = null
                notifyPublication(recovered)
                return true
            }
        }
        if (guard == Guard.CORRUPT_UNCOPIED) {
            if (keepCorruptCopy() == null) return false
            synchronized(replay) {
                replay.clear()
                guard = Guard.NONE
            }
            noticeFlow.value = PrefsNotice.CORRUPT_COPY_KEPT
        }
        return true
    }

    companion object {
        /** Coalesces a burst of changes, such as a dragged slider, into one write. */
        const val WRITE_DELAY_MILLIS = 500L
        const val RETRY_MILLIS = 5_000L
        private const val TAG = "PrefsStore"
    }
}
