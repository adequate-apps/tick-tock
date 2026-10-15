// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.history

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import ticktock.core.contracts.HistorySink
import ticktock.core.detect.Reading
import ticktock.core.detect.Hop
import ticktock.core.prefs.PrefsSchema
import ticktock.data.StorageThread
import ticktock.data.db.HistoryDatabase
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** What the store reports for Diagnostics and the write-failure banner. */
data class StoreStatus(
    /** True after [HistoryStore.FAILURES_BEFORE_BANNER] flushes in a row have failed (SES-07). */
    val failing: Boolean = false,
    val failedFlushes: Long = 0,
    val lastError: String? = null,
    /** Successful flushes; the table refreshes on a change here, never on a timer of its own (SES-08). */
    val flushes: Long = 0,
    val rejected: Int = 0,
    val skippedWhilePaused: Long = 0,
    /** Readings dropped because the retry buffer was full during a long write failure. */
    val droppedOnOverflow: Long = 0,
    val readingsDeletedByRetention: Long = 0,
)

/** Which runs a table or a summary covers (SES-03). */
sealed class Scope {
    /** Runs since the current session started. */
    data object CurrentSession : Scope()

    data class Session(val id: Long) : Scope()

    /** Every stored run; drone runs only when asked for (CTX-03). */
    data class AllHistory(val includeDroneRuns: Boolean = false) : Scope()
}

/** The labels that keep one horn's statistics apart from another's (SES-04). */
data class RunLabel(val instrumentKey: String, val transposition: Int, val a4Hz: Double, val nickname: String)

data class NoteSummary(
    val label: RunLabel,
    val midiSounding: Int,
    val n: Long,
    val meanCents: Double,
    /** Population standard deviation of the readings' cents. */
    val spreadCents: Double,
    val attacks: Long,
    /** Mean cents of the first settled reading of each attack, or NaN with no attacks (ATK-02). */
    val meanAttackCents: Double,
    val bins: IntArray,
)

data class SessionRow(
    val id: Long,
    val startedAt: Long,
    /** Empty until the player saves the session; a named session is a saved one. */
    val name: String,
    val equipment: String,
    val runs: Int,
    val readings: Long,
    val current: Boolean,
    val purpose: SessionPurpose = SessionPurpose.PRACTICE,
    val notes: String = "",
    val attacks: Long = 0,
    val distinctNotes: Int = 0,
) {
    /** Accepted capture time survives raw-reading retention and excludes time between visits. */
    val capturedSeconds: Double get() = readings * Hop.SECONDS
}

enum class SessionPurpose(val sql: String) { PRACTICE("practice"), SHOP("shop"), EQUIPMENT("equipment") }

/** One note_hist row, retaining the labels that an aggregate table combines. */
data class ExportSummary(
    val note: NoteSummary,
    val kind: RunKind,
    val equipment: String,
    val session: String,
    val startedAt: Long,
)

data class HistorySnapshot(
    val rows: List<NoteSummary>,
    val history: List<NoteSummary>,
    val sessions: List<SessionRow>,
    val currentSessionId: Long?,
    val scopeSessions: Int,
    val since: Long?,
)

/**
 * The history writer (SES-05..10, CAP, CTX, ATK). The analysis thread calls
 * [accept]; the reading is validated, labelled with the current [RunContext]
 * and queued, and the storage thread writes the queue in one transaction about
 * once a second (SES-06). Sessions start on the first stored reading after
 * launch or after [newSession]; runs open whenever the label or the A4
 * generation changes (SES-02). A failed flush keeps the queue for the next
 * attempt and raises [status] after three failures in a row (SES-07).
 *
 * Run and session state lives on the storage thread only. A transaction's
 * changes to that state are drafted and kept only if the transaction commits,
 * so a failed write leaves memory and disk agreeing.
 */
class HistoryStore(
    private val database: HistoryDatabase,
    private val storage: StorageThread,
    initialContext: RunContext,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : HistorySink {

    @Volatile
    private var context: RunContext = initialContext

    private val lock = Any()
    private val pending = ArrayDeque<Pending>()
    private var lastQueued: RunIdentity? = null

    /** Entries pushed out of a full queue, counted on both threads. */
    private val dropped = AtomicLong()

    private val rejectedCount = AtomicInteger()
    private val pausedCount = AtomicLong()
    private val statusFlow = MutableStateFlow(StoreStatus())
    val status: StateFlow<StoreStatus> = statusFlow

    /** Transactions begun on the history database, for the SES-06 rate proof. */
    @Volatile
    var transactions: Long = 0
        private set

    // Storage-thread state.
    private var sessionId: Long? = null
    private var run: OpenRun? = null
    private var notes = HashMap<Int, NoteAggregate>()
    private var consecutiveFailures = 0
    private var flushTask: ScheduledFuture<*>? = null
    private var maintenanceTask: ScheduledFuture<*>? = null

    override val rejected: Int get() = rejectedCount.get()

    /** Called from the UI or control thread whenever a run label changes; takes effect from the next reading. */
    fun setContext(next: RunContext) {
        context = next
    }

    override fun accept(reading: Reading, firstSettled: Boolean) {
        if (!HistorySink.storable(reading)) {
            rejectedCount.incrementAndGet()
            publish { it.copy(rejected = rejectedCount.get()) }
            return
        }
        val ctx = context
        val identity = RunIdentity(ctx, reading.a4Hz, reading.a4Generation)
        val now = wallClock()
        synchronized(lock) {
            if (!ctx.captured) {
                pausedCount.incrementAndGet()
                // The run a paused stretch would have opened is still recorded,
                // empty and marked uncaptured (CAP-03); its readings are not.
                if (identity != lastQueued) enqueue(Pending(null, false, identity, now))
            } else {
                enqueue(Pending(reading, firstSettled, identity, now))
            }
            lastQueued = identity
        }
        if (!ctx.captured) publish { it.copy(skippedWhilePaused = pausedCount.get()) }
    }

    /** Caller holds [lock]. */
    private fun enqueue(entry: Pending) {
        pending.addLast(entry)
        if (pending.size > MAX_PENDING) {
            pending.removeFirst()
            dropped.incrementAndGet()
        }
    }

    /** Starts the once-a-second flush and the daily retention pass, which also runs now (SES-09). */
    fun start() {
        storage.post {
            if (flushTask != null) return@post
            maintenanceTask = storage.every(DAY_MILLIS, initialDelayMillis = 0) { runCatchingLogged("retention") { applyRetention() } }
            flushTask = storage.every(FLUSH_PERIOD_MILLIS) { flush() }
        }
    }

    /** Stops the schedule after one last flush; the caller closes the database. */
    fun stop() {
        storage.call {
            flushTask?.cancel(false)
            maintenanceTask?.cancel(false)
            flushTask = null
            maintenanceTask = null
            flush()
        }
    }

    /** Writes what is queued now without waiting; the lifecycle owner calls it when the app goes to the background. */
    fun flushSoon() = storage.post { flush() }

    /** Writes what is queued now and waits; used when the app goes to the background. Returns false if the write failed. */
    fun flushAndWait(): Boolean = storage.call { flush() }

    /** Storage thread only. Writes every queued entry in one transaction. */
    private fun flush(): Boolean {
        val batch = synchronized(lock) {
            if (pending.isEmpty()) return true
            ArrayList(pending).also { pending.clear() }
        }
        var draftSession = sessionId
        var draftRun = run
        var draftNotes = HashMap(notes.mapValues { it.value.copy() })
        val touched = LinkedHashMap<Pair<Long, Int>, NoteAggregate>()
        try {
            val db = database.writableDatabase
            transactions++
            db.beginTransaction()
            try {
                for (entry in batch) {
                    val session = draftSession ?: insertSession(db, entry.ts).also { draftSession = it }
                    val open = draftRun
                    val current = if (open != null && open.sessionId == session && open.identity == entry.identity) {
                        open
                    } else {
                        draftNotes = HashMap()
                        OpenRun(insertRun(db, session, entry), session, entry.identity).also { draftRun = it }
                    }
                    val reading = entry.reading ?: continue
                    insertReading(db, current.id, reading, entry.ts)
                    val aggregate = draftNotes.getOrPut(reading.midiSounding) { NoteAggregate() }
                    aggregate.add(reading.cents, entry.firstSettled)
                    touched[current.id to reading.midiSounding] = aggregate
                }
                touched.forEach { (key, aggregate) -> writeSummary(db, key.first, key.second, aggregate) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (failure: RuntimeException) {
            // Keep the batch, oldest first, ahead of anything queued since (SES-07).
            synchronized(lock) {
                for (i in batch.indices.reversed()) pending.addFirst(batch[i])
                while (pending.size > MAX_PENDING) {
                    pending.removeFirst()
                    dropped.incrementAndGet()
                }
            }
            consecutiveFailures++
            Log.w(TAG, "history flush failed ($consecutiveFailures in a row)", failure)
            publish {
                it.copy(
                    failing = consecutiveFailures >= FAILURES_BEFORE_BANNER,
                    failedFlushes = it.failedFlushes + 1,
                    lastError = failure.toString(),
                    droppedOnOverflow = dropped.get(),
                )
            }
            return false
        }
        sessionId = draftSession
        run = draftRun
        notes = draftNotes
        consecutiveFailures = 0
        publish { it.copy(failing = false, flushes = it.flushes + 1, droppedOnOverflow = dropped.get()) }
        return true
    }

    private fun insertSession(db: SQLiteDatabase, startedAt: Long): Long =
        db.insertOrThrow("sessions", null, ContentValues().apply { put("started_at", startedAt) })

    private fun insertRun(db: SQLiteDatabase, session: Long, entry: Pending): Long {
        val ctx = entry.identity.context
        return db.insertOrThrow(
            "runs",
            null,
            ContentValues().apply {
                put("session_id", session)
                put("started_at", entry.ts)
                put("instrument_key", ctx.instrumentKey)
                put("transposition", ctx.transposition)
                put("a4_hz", entry.identity.a4Hz)
                put("a4_gen", entry.identity.a4Generation)
                put("nickname", ctx.nickname)
                put("context", ctx.kind.sql)
                // 2.0 grades against equal temperament only; "just" is reserved for v1.1 (CTX-04).
                put("target", TARGET_EQUAL)
                put("captured", if (ctx.captured) 1 else 0)
                if (ctx.kind == RunKind.DRONE && ctx.droneMidi != null) put("drone_midi", ctx.droneMidi) else putNull("drone_midi")
            },
        )
    }

    private fun insertReading(db: SQLiteDatabase, runId: Long, r: Reading, ts: Long) {
        db.insertOrThrow(
            "readings",
            null,
            ContentValues().apply {
                put("run_id", runId)
                put("hop", r.hop)
                put("midi_sounding", r.midiSounding)
                put("hz", r.hz)
                put("cents", r.cents)
                put("ts", ts)
            },
        )
    }

    private fun writeSummary(db: SQLiteDatabase, runId: Long, midi: Int, a: NoteAggregate) {
        db.insertWithOnConflict(
            "note_hist",
            null,
            ContentValues().apply {
                put("run_id", runId)
                put("midi_sounding", midi)
                put("n", a.n)
                put("sum_cents", a.sumCents)
                put("sum_sq", a.sumSq)
                put("bins", a.binsBlob())
                put("n_attacks", a.attacks)
                put("sum_attack_cents", a.sumAttackCents)
                put("sum_attack_sq", a.sumAttackSq)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Deletes raw readings older than the retention period from unsaved
     * sessions; summaries stay (SES-09). A saved session keeps its readings
     * until the player deletes it (decision 5). Storage thread only.
     */
    private fun applyRetention() {
        val cutoff = wallClock() - RETENTION_DAYS * DAY_MILLIS
        val deleted = database.writableDatabase.delete(
            "readings",
            "ts < ? AND run_id IN (SELECT r.id FROM runs r JOIN sessions s ON s.id = r.session_id WHERE s.name = '')",
            arrayOf(cutoff.toString()),
        )
        if (deleted > 0) publish { it.copy(readingsDeletedByRetention = it.readingsDeletedByRetention + deleted) }
    }

    /** Runs retention now; tests and Diagnostics use it. */
    fun applyRetentionNow() = storage.call { applyRetention() }

    // Sessions (decision 5: named sessions ship in 2.0).

    /**
     * Starts a new session at the next stored reading. Readings queued before
     * the call belong to the old session, so they are written first; if that
     * write fails the change is refused and nothing moves (SES-07).
     */
    suspend fun newSession() = onStorage {
        flushOrThrow()
        leaveSession(next = null)
    }

    /** Continues a saved session: the next run opens inside it. */
    suspend fun resumeSession(id: Long) = onStorage {
        flushOrThrow()
        require(exists(id)) { "no session $id" }
        leaveSession(next = id)
    }

    /** Storage thread only. Closes the open run; the next reading, captured or not, opens one in [next] or a new session. */
    private fun leaveSession(next: Long?) {
        sessionId = next
        run = null
        notes = HashMap()
        // A paused stretch records the empty run its new session opens (CAP-03).
        synchronized(lock) { lastQueued = null }
    }

    private fun flushOrThrow() {
        if (!flush()) throw HistoryWriteException(status.value.lastError ?: "the history write failed")
    }

    /** Names (saves) a session and records its equipment (EQP-02). */
    suspend fun saveSession(id: Long, name: String, equipment: String,
        purpose: SessionPurpose = SessionPurpose.PRACTICE, notes: String = "") = onStorage {
        val cleanName = name.trim().take(PrefsSchema.TEXT_MAX)
        require(cleanName.isNotEmpty()) { "a saved session needs a name" }
        flushOrThrow()
        val values = ContentValues().apply {
            put("name", cleanName)
            put("equipment", equipment.trim().take(PrefsSchema.TEXT_MAX))
            put("purpose", purpose.sql)
            put("notes", notes.trim().take(SESSION_NOTES_MAX))
        }
        val updated = database.writableDatabase.update("sessions", values, "id = ?", arrayOf(id.toString()))
        require(updated == 1) { "no session $id" }
    }

    /** Deletes a session with its runs, summaries and readings. The caller has already confirmed with the player. */
    suspend fun deleteSession(id: Long) = onStorage {
        // Queued readings may belong to this session; once they are written the
        // delete takes them too, and none come back inside the next session.
        flushOrThrow()
        database.writableDatabase.delete("sessions", "id = ?", arrayOf(id.toString()))
        if (sessionId == id) leaveSession(next = null)
    }

    suspend fun currentSessionId(): Long? = onStorage { sessionId }

    suspend fun sessions(namedOnly: Boolean = false): List<SessionRow> = onStorage {
        val current = sessionId
        val where = if (namedOnly) "WHERE s.name != ''" else ""
        database.readableDatabase.rawQuery(
            """SELECT s.id, s.started_at, s.name, s.equipment,
                      (SELECT COUNT(*) FROM runs r WHERE r.session_id = s.id),
                      (SELECT COALESCE(SUM(h.n), 0) FROM note_hist h JOIN runs r ON r.id = h.run_id WHERE r.session_id = s.id),
                      s.purpose, s.notes,
                      (SELECT COALESCE(SUM(h.n_attacks), 0) FROM note_hist h JOIN runs r ON r.id = h.run_id WHERE r.session_id = s.id),
                      (SELECT COUNT(DISTINCT h.midi_sounding) FROM note_hist h JOIN runs r ON r.id = h.run_id WHERE r.session_id = s.id)
               FROM sessions s $where ORDER BY s.started_at DESC, s.id DESC""",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(SessionRow(id, c.getLong(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5), id == current,
                        SessionPurpose.entries.first { it.sql == c.getString(6) }, c.getString(7), c.getLong(8), c.getInt(9)))
                }
            }
        }
    }

    /** Per-label, per-note statistics for [scope], from the stored summaries (SES-03, SES-04, SES-08). */
    suspend fun summaries(scope: Scope): List<NoteSummary> = onStorage {
        val (where, args) = selection(scope)
        val groups = LinkedHashMap<Pair<RunLabel, Int>, NoteAggregate>()
        database.readableDatabase.rawQuery(
            """SELECT r.instrument_key, r.transposition, r.a4_hz, r.nickname, h.midi_sounding,
                      h.n, h.sum_cents, h.sum_sq, h.bins, h.n_attacks, h.sum_attack_cents, h.sum_attack_sq
               FROM note_hist h JOIN runs r ON r.id = h.run_id
               WHERE $where ORDER BY r.instrument_key, r.nickname, r.a4_hz, h.midi_sounding""",
            args,
        ).use { c ->
            while (c.moveToNext()) {
                val label = RunLabel(c.getString(0), c.getInt(1), c.getDouble(2), c.getString(3))
                val total = groups.getOrPut(label to c.getInt(4)) { NoteAggregate() }
                total.n += c.getLong(5)
                total.sumCents += c.getDouble(6)
                total.sumSq += c.getDouble(7)
                NoteAggregate.binsFrom(c.getBlob(8)).forEachIndexed { i, count -> total.bins[i] += count }
                total.attacks += c.getLong(9)
                total.sumAttackCents += c.getDouble(10)
                total.sumAttackSq += c.getDouble(11)
            }
        }
        groups.map { (key, a) ->
            val mean = a.sumCents / a.n
            NoteSummary(
                label = key.first,
                midiSounding = key.second,
                n = a.n,
                meanCents = mean,
                spreadCents = sqrt((a.sumSq / a.n - mean * mean).coerceAtLeast(0.0)),
                attacks = a.attacks,
                meanAttackCents = if (a.hasAttacks) a.sumAttackCents / a.attacks else Double.NaN,
                bins = a.bins,
            )
        }
    }

    /** CSV uses stored rows so session, equipment and capture context survive export (EXP-01). */
    suspend fun export(scope: Scope): List<ExportSummary> = onStorage {
        flushOrThrow()
        val (where, args) = selection(scope)
        database.readableDatabase.rawQuery(
            """SELECT r.instrument_key, r.transposition, r.a4_hz, r.nickname, h.midi_sounding,
                      h.n, h.sum_cents, h.sum_sq, h.bins, h.n_attacks, h.sum_attack_cents,
                      r.context, s.equipment, s.name, r.started_at
               FROM note_hist h JOIN runs r ON r.id = h.run_id JOIN sessions s ON s.id = r.session_id
               WHERE $where ORDER BY s.started_at, s.id, r.id, h.midi_sounding""", args,
        ).use { c -> buildList {
            while (c.moveToNext()) {
                val n = c.getLong(5)
                val mean = c.getDouble(6) / n
                val attacks = c.getLong(9)
                add(ExportSummary(NoteSummary(RunLabel(c.getString(0), c.getInt(1), c.getDouble(2), c.getString(3)),
                    c.getInt(4), n, mean, sqrt((c.getDouble(7) / n - mean * mean).coerceAtLeast(0.0)), attacks,
                    if (attacks > 0) c.getDouble(10) / attacks else Double.NaN, NoteAggregate.binsFrom(c.getBlob(8))),
                    RunKind.of(c.getString(11)), c.getString(12), c.getString(13), c.getLong(14)))
            }
        } }
    }

    /** Every table fact comes from one storage turn, between scheduled flushes. */
    suspend fun snapshot(scope: Scope, includeDroneRuns: Boolean, includeHistory: Boolean = true): HistorySnapshot = withContext(storage.dispatcher) {
        val (where, args) = selection(scope)
        val totals = database.readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT r.session_id), MIN(r.started_at) FROM runs r JOIN note_hist h ON h.run_id = r.id WHERE $where", args,
        ).use { c -> c.moveToFirst(); c.getInt(0) to if (c.isNull(1)) null else c.getLong(1) }
        HistorySnapshot(summaries(scope), if (includeHistory) summaries(Scope.AllHistory(includeDroneRuns)) else emptyList(), sessions(), sessionId, totals.first, totals.second)
    }

    /** Clears a confirmed note in the displayed scope, including raw rows and cached aggregates. */
    suspend fun clearNote(scope: Scope, label: RunLabel, midi: Int) = onStorage {
        flushOrThrow()
        val (where, args) = selection(scope)
        val runs = """SELECT r.id FROM runs r WHERE ($where) AND r.instrument_key = ? AND r.transposition = ?
            AND r.a4_hz = ? AND r.nickname = ?"""
        val values = args + arrayOf(label.instrumentKey, label.transposition.toString(), label.a4Hz.toString(), label.nickname, midi.toString())
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("readings", "note_hist")) db.delete(table, "run_id IN ($runs) AND midi_sounding = ?", values)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // A new run prevents the live in-memory aggregate from bringing cleared readings back.
        leaveSession(sessionId)
        publish { it.copy(flushes = it.flushes + 1) }
    }

    /** Settings calls this only after a confirmation covering every stored session. */
    suspend fun clearHistory() = onStorage {
        flushOrThrow()
        database.writableDatabase.delete("sessions", null, null)
        leaveSession(null)
        publish { it.copy(flushes = it.flushes + 1) }
    }

    private fun selection(scope: Scope): Pair<String, Array<String>> = when (scope) {
        Scope.CurrentSession -> sessionId?.let { "r.session_id = ?" to arrayOf(it.toString()) } ?: ("0" to emptyArray())
        is Scope.Session -> "r.session_id = ?" to arrayOf(scope.id.toString())
        is Scope.AllHistory -> (if (scope.includeDroneRuns) "1" else "r.context != '${RunKind.DRONE.sql}'") to emptyArray()
    }

    /** The database file's size in bytes, from its page count (SES-10). */
    fun databaseBytes(): Long = storage.call {
        val db = database.readableDatabase
        val pages = db.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) }
        pages * db.pageSize
    }

    private fun exists(id: Long): Boolean =
        database.readableDatabase.rawQuery("SELECT 1 FROM sessions WHERE id = ?", arrayOf(id.toString())).use { it.moveToFirst() }

    private suspend fun <T> onStorage(block: () -> T): T = withContext(storage.dispatcher) { block() }

    private fun publish(update: (StoreStatus) -> StoreStatus) {
        while (true) {
            val before = statusFlow.value
            if (statusFlow.compareAndSet(before, update(before))) return
        }
    }

    private inline fun runCatchingLogged(what: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: RuntimeException) {
            // Retention runs again tomorrow; the failure is counted where the player can see it.
            Log.w(TAG, "$what failed", failure)
            publish { it.copy(lastError = failure.toString()) }
        }
    }

    private data class RunIdentity(val context: RunContext, val a4Hz: Double, val a4Generation: Int)

    private class OpenRun(val id: Long, val sessionId: Long, val identity: RunIdentity)

    private class Pending(val reading: Reading?, val firstSettled: Boolean, val identity: RunIdentity, val ts: Long)

    companion object {
        const val FLUSH_PERIOD_MILLIS = 1_000L
        const val FAILURES_BEFORE_BANNER = 3
        const val RETENTION_DAYS = 7L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val SESSION_NOTES_MAX = 2_000

        /** About fifteen minutes of readings at 21.5 a second: room for a long write outage, bounded memory. */
        const val MAX_PENDING = 20_000

        private const val TARGET_EQUAL = "equal"
        private const val TAG = "HistoryStore"
    }
}

/** A history write failed, so the session change that needed it did not happen (SES-07). */
class HistoryWriteException(message: String) : RuntimeException(message)
