// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.core.detect.Reading
import ticktock.data.StorageThread
import ticktock.data.db.HistoryDatabase
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
class HistoryStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val database = FlakyDatabase(context)
    private val storage = StorageThread("storage-test")
    private var now = 1_800_000_000_000L
    private val alto = RunContext(instrumentKey = "alto-sax", transposition = -9)
    private val store = HistoryStore(database, storage, alto) { now }
    private var hop = 0L

    @After
    fun tearDown() {
        storage.shutdown()
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun reading(midi: Int = 69, cents: Double = 0.0, a4: Double = 440.0, gen: Int = 0) =
        Reading(hop = hop++, midiSounding = midi, hz = a4 * 2.0.pow((midi - 69 + cents / 100) / 12), cents = cents, a4Hz = a4, a4Generation = gen)

    private fun db(): SQLiteDatabase = database.readableDatabase

    private fun count(sql: String, vararg args: String): Long =
        storage.call { db().rawQuery(sql, args).use { it.moveToFirst(); it.getLong(0) } }

    private fun runs(): List<Map<String, Any?>> = storage.call {
        db().rawQuery("SELECT id, session_id, instrument_key, transposition, a4_hz, a4_gen, nickname, context, target, captured, drone_midi FROM runs ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).associate { i ->
                        c.getColumnName(i) to when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> c.getString(i)
                        }
                    })
                }
            }
        }
    }

    @Test
    fun `SES-01 the first run carries the saved A4 and nickname`() {
        store.setContext(alto.copy(nickname = "Horn B"))
        store.accept(reading(a4 = 442.0, gen = 0))
        assertTrue(store.flushAndWait())

        val run = runs().single()
        assertEquals(442.0, run["a4_hz"])
        assertEquals("Horn B", run["nickname"])
        assertEquals("alto-sax", run["instrument_key"])
        assertEquals(-9L, run["transposition"])
    }

    @Test
    fun `SES-02 nickname, A4, instrument and transposition changes each open a run`() {
        store.setContext(alto.copy(nickname = "Horn A"))
        store.accept(reading())
        store.accept(reading())
        store.setContext(alto.copy(nickname = "Horn B"))
        store.accept(reading())
        store.accept(reading(gen = 1)) // A4 changed, even to the same value
        store.setContext(alto.copy(nickname = "Horn B", instrumentKey = "tenor-sax"))
        store.accept(reading(gen = 1))
        store.setContext(alto.copy(nickname = "Horn B", instrumentKey = "tenor-sax", transposition = -14))
        store.accept(reading(gen = 1))
        store.setContext(alto.copy(nickname = "", instrumentKey = "tenor-sax", transposition = -14))
        store.accept(reading(gen = 1))
        store.flushAndWait()

        val runs = runs()
        assertEquals(6, runs.size)
        assertEquals(listOf("Horn A", "Horn B", "Horn B", "Horn B", "Horn B", ""), runs.map { it["nickname"] })
        assertEquals(listOf(0L, 0L, 1L, 1L, 1L, 1L), runs.map { it["a4_gen"] })
        assertEquals(1, runs.map { it["session_id"] }.toSet().size)
    }

    @Test
    fun `SES-03 the session scope holds only this session and All history holds both`() = runBlocking {
        store.accept(reading(midi = 60))
        store.flushAndWait()
        now += HistoryStore.DAY_MILLIS
        store.newSession()
        store.accept(reading(midi = 62))
        store.flushAndWait()

        assertEquals(listOf(62), store.summaries(Scope.CurrentSession).map { it.midiSounding })
        assertEquals(listOf(60, 62), store.summaries(Scope.AllHistory()).map { it.midiSounding }.sorted())
        assertEquals(2, store.sessions().size)
    }

    @Test
    fun `SES-04 two nicknames in one session keep separate statistics`() = runBlocking {
        store.setContext(alto.copy(nickname = "Horn A"))
        repeat(4) { store.accept(reading(cents = 10.0)) }
        store.setContext(alto.copy(nickname = "Horn B"))
        repeat(4) { store.accept(reading(cents = -6.0)) }
        store.flushAndWait()

        val byNick = store.summaries(Scope.CurrentSession).associateBy { it.label.nickname }
        assertEquals(10.0, byNick.getValue("Horn A").meanCents, 1e-9)
        assertEquals(-6.0, byNick.getValue("Horn B").meanCents, 1e-9)
        assertEquals(4L, byNick.getValue("Horn A").n)
    }

    @Test
    fun `SES-05 invalid readings are counted and never stored`() {
        store.accept(reading().copy(hz = Double.NaN))
        store.accept(reading().copy(cents = 50.5))
        store.accept(reading().copy(midiSounding = 128))
        store.accept(reading().copy(hz = 25_000.0))
        store.accept(reading())
        store.flushAndWait()

        assertEquals(4, store.rejected)
        assertEquals(4, store.status.value.rejected)
        assertEquals(1L, count("SELECT COUNT(*) FROM readings"))
    }

    @Test
    fun `SES-06 a burst of readings is one transaction`() {
        repeat(200) { store.accept(reading(cents = (it % 9).toDouble())) }
        val before = store.transactions
        store.flushAndWait()
        assertEquals(1L, store.transactions - before)
        assertEquals(200L, count("SELECT COUNT(*) FROM readings"))
    }

    @Test
    fun `SES-06 continuous playing writes at most once a second`() {
        store.start()
        val started = System.nanoTime()
        val until = started + 3_200_000_000L
        while (System.nanoTime() < until) {
            store.accept(reading())
            Thread.sleep(5) // about 200 readings a second, ten times the hop rate
        }
        store.stop()
        val elapsedSeconds = (System.nanoTime() - started) / 1e9
        // Scheduled flushes at most one per second (the first after one second), plus the closing flush from stop().
        assertTrue("${store.transactions} transactions in $elapsedSeconds s", store.transactions <= elapsedSeconds.toLong() + 1)
        assertEquals(hop, count("SELECT COUNT(*) FROM readings"))
    }

    @Test
    fun `SES-07 a failing flush keeps the buffer, counts, raises the banner after three, and recovers`() {
        repeat(10) { store.accept(reading()) }
        database.failWrites = true
        repeat(2) { assertFalse(store.flushAndWait()) }
        assertFalse(store.status.value.failing)
        store.accept(reading())
        assertFalse(store.flushAndWait())
        assertTrue("three in a row show the banner", store.status.value.failing)
        assertEquals(3L, store.status.value.failedFlushes)

        database.failWrites = false
        assertTrue(store.flushAndWait())
        assertFalse(store.status.value.failing)
        assertEquals("nothing lost across the failures", 11L, count("SELECT COUNT(*) FROM readings"))
        assertEquals("the run was created once", 1, runs().size)
    }

    @Test
    fun `SES-08 summaries carry count, mean, spread and the histogram, and every flush is announced`() = runBlocking {
        val cents = listOf(-4.0, -2.0, 0.0, 2.0, 4.0)
        cents.forEach { store.accept(reading(midi = 64, cents = it)) }
        store.flushAndWait()
        store.accept(reading(midi = 64, cents = 4.0))
        store.flushAndWait()

        val s = store.summaries(Scope.CurrentSession).single()
        val all = cents + 4.0
        val mean = all.average()
        assertEquals(6L, s.n)
        assertEquals(mean, s.meanCents, 1e-9)
        assertEquals(kotlin.math.sqrt(all.sumOf { (it - mean) * (it - mean) } / all.size), s.spreadCents, 1e-9)
        assertEquals(2, s.bins[50 + 4])
        assertEquals(1, s.bins[50 - 4])
        assertEquals(2L, store.status.value.flushes)
    }

    @Test
    fun `SES-09 retention deletes old raw readings and keeps their summaries`() = runBlocking {
        store.accept(reading(midi = 60))
        store.flushAndWait()
        now += 8 * HistoryStore.DAY_MILLIS
        store.accept(reading(midi = 60))
        store.flushAndWait()
        store.applyRetentionNow()

        assertEquals(1L, count("SELECT COUNT(*) FROM readings"))
        assertEquals(2L, store.summaries(Scope.AllHistory()).single().n)
        assertEquals(1L, store.status.value.readingsDeletedByRetention)
    }

    @Test
    fun `SES-09 a saved session keeps its raw readings past the retention period`() = runBlocking {
        store.accept(reading(midi = 60))
        store.flushAndWait()
        store.saveSession(store.currentSessionId()!!, "Etude 12", "")
        store.newSession()
        store.accept(reading(midi = 62))
        store.flushAndWait()
        now += 8 * HistoryStore.DAY_MILLIS
        store.applyRetentionNow()

        assertEquals("the saved session's reading stays; the unsaved one goes", 1L, count("SELECT COUNT(*) FROM readings"))
        assertEquals(60L, count("SELECT midi_sounding FROM readings"))
    }

    @Test
    fun `SES-07 a session change whose flush fails is refused and files nothing under the next session`() = runBlocking {
        store.accept(reading(midi = 60))
        store.flushAndWait()
        val first = store.currentSessionId()!!
        store.accept(reading(midi = 62))
        database.failWrites = true
        val refused = runCatching { store.newSession() }
        assertTrue("the failure reaches the caller", refused.exceptionOrNull() is HistoryWriteException)
        database.failWrites = false
        store.accept(reading(midi = 64))
        store.flushAndWait()

        assertEquals(first, store.currentSessionId())
        assertEquals(listOf(60, 62, 64), store.summaries(Scope.Session(first)).map { it.midiSounding })
    }

    @Test
    fun `CAP-03 a new session while paused still records its uncaptured run`() = runBlocking {
        store.setContext(alto.copy(captured = false))
        store.accept(reading())
        store.flushAndWait()
        store.newSession()
        repeat(3) { store.accept(reading()) }
        store.flushAndWait()
        assertEquals(2L, count("SELECT COUNT(*) FROM runs WHERE captured = 0"))
    }

    /**
     * SES-10: a year of 30 minutes of sounding notes a day stays under 100 MB.
     * Raw readings only live for the retention period, so the last eight days
     * run at full rate (about 21.5 readings a second) and the earlier days
     * store the same runs and notes with one reading each, which leaves the
     * same summary rows once retention has run.
     */
    @Test
    fun `SES-10 a simulated year stays under 100 MB`() {
        val runsPerDay = 10
        val notesPerRun = 40
        val fullDayReadings = 1_800 * 215 / 10
        for (day in 0 until 365) {
            val full = day >= 365 - 8
            val perNote = if (full) fullDayReadings / (runsPerDay * notesPerRun) else 1
            for (r in 0 until runsPerDay) {
                store.setContext(alto.copy(nickname = "Horn ${r % 3}", transposition = -9 - (day + r) % 2))
                for (note in 0 until notesPerRun) {
                    repeat(perNote) { store.accept(reading(midi = 45 + note, cents = ((it * 7) % 61 - 30).toDouble(), gen = day * runsPerDay + r)) }
                }
                if (full) store.flushAndWait()
            }
            store.flushAndWait()
            now += HistoryStore.DAY_MILLIS
            store.applyRetentionNow()
        }
        val bytes = store.databaseBytes()
        assertEquals(365L * runsPerDay, count("SELECT COUNT(*) FROM runs"))
        assertTrue("database is $bytes bytes", bytes < 100L * 1024 * 1024)
    }

    @Test
    fun `ATK-02 an attack counts once and adds its first settled reading`() = runBlocking {
        store.accept(reading(midi = 67, cents = 8.0), firstSettled = true)
        repeat(214) { store.accept(reading(midi = 67, cents = 2.0)) }
        store.accept(reading(midi = 67, cents = -4.0), firstSettled = true)
        store.flushAndWait()

        val s = store.summaries(Scope.CurrentSession).single()
        assertEquals(216L, s.n)
        assertEquals(2L, s.attacks)
        assertEquals(2.0, s.meanAttackCents, 1e-9)
    }

    @Test
    fun `CAP-01 and CAP-03 paused readings are counted and only the empty run is recorded`() {
        store.accept(reading())
        store.setContext(alto.copy(captured = false))
        repeat(5) { store.accept(reading()) }
        store.accept(reading(gen = 1)) // an A4 change while paused opens an uncaptured run
        store.flushAndWait()

        assertEquals(1L, count("SELECT COUNT(*) FROM readings"))
        assertEquals(listOf(1L, 0L, 0L), runs().map { it["captured"] })
        assertEquals(6L, store.status.value.skippedWhilePaused)
    }

    @Test
    fun `CTX-02 to CTX-04 drone runs are labelled, left out of All history by default, and target stays equal`() = runBlocking {
        store.accept(reading(midi = 60))
        store.setContext(alto.copy(kind = RunKind.DRONE, droneMidi = 57))
        store.accept(reading(midi = 62))
        store.setContext(alto)
        store.accept(reading(midi = 64))
        store.flushAndWait()

        val runs = runs()
        assertEquals(listOf("free", "drone", "free"), runs.map { it["context"] })
        assertEquals(listOf(null, 57L, null), runs.map { it["drone_midi"] })
        assertTrue(runs.all { it["target"] == "equal" })
        assertEquals(listOf(60, 64), store.summaries(Scope.AllHistory()).map { it.midiSounding })
        assertEquals(listOf(60, 62, 64), store.summaries(Scope.AllHistory(includeDroneRuns = true)).map { it.midiSounding }.sorted())
    }

    @Test
    fun `named sessions save, list, resume and delete`() = runBlocking {
        store.accept(reading(midi = 60))
        store.flushAndWait()
        val first = store.currentSessionId()!!
        store.saveSession(first, "  Etude 12  ", "Vandoren V16 #6")
        store.newSession()
        store.accept(reading(midi = 62))
        store.flushAndWait()
        val second = store.currentSessionId()!!

        val named = store.sessions(namedOnly = true).single()
        assertEquals("Etude 12", named.name)
        assertEquals("Vandoren V16 #6", named.equipment)
        assertFalse(named.current)

        store.resumeSession(first)
        store.accept(reading(midi = 65))
        store.flushAndWait()
        assertEquals(first, store.currentSessionId())
        assertEquals(listOf(60, 65), store.summaries(Scope.Session(first)).map { it.midiSounding })

        store.deleteSession(first)
        assertEquals(null, store.currentSessionId())
        assertEquals(listOf(second), store.sessions().map { it.id })
        assertEquals(0L, count("SELECT COUNT(*) FROM readings r JOIN runs u ON u.id = r.run_id WHERE u.session_id = ?", first.toString()))
        store.accept(reading(midi = 67))
        store.flushAndWait()
        assertEquals("a reading after deleting the current session starts a new one", 2, store.sessions().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a saved session needs a name`() = runBlocking {
        store.accept(reading())
        store.flushAndWait()
        store.saveSession(store.currentSessionId()!!, "   ", "")
    }

    @Test
    fun `EQP-02 saved metadata and captured time survive raw retention and session resumption`() = runBlocking {
        repeat(20) { store.accept(reading(), firstSettled = it % 10 == 0) }
        store.flushAndWait()
        val id = store.currentSessionId()!!
        store.newSession()
        now += 10 * HistoryStore.DAY_MILLIS
        store.applyRetentionNow()
        assertEquals(0L, count("SELECT COUNT(*) FROM readings"))
        store.saveSession(id, " Study ", " Reed ", SessionPurpose.SHOP, " Cold start\nNew reed ")
        store.resumeSession(id)
        repeat(10) { store.accept(reading(midi = 71), firstSettled = it == 0) }
        store.flushAndWait()
        val row = store.sessions(namedOnly = true).single()
        assertEquals("Study", row.name)
        assertEquals("Reed", row.equipment)
        assertEquals(SessionPurpose.SHOP, row.purpose)
        assertEquals("Cold start\nNew reed", row.notes)
        assertEquals(30L, row.readings)
        assertEquals(3L, row.attacks)
        assertEquals(2, row.distinctNotes)
        assertEquals(30 * ticktock.core.detect.Hop.SECONDS, row.capturedSeconds, 1e-9)
        assertTrue(row.current)
    }

    @Test
    fun `EXP-01 export preserves each stored context equipment session and graded A4`() = runBlocking {
        store.accept(reading(cents = 3.2), firstSettled = true)
        store.accept(reading(cents = 5.2))
        store.flushAndWait()
        val first = store.currentSessionId()!!
        store.saveSession(first, "Morning", "Reed A")
        store.setContext(alto.copy(kind = RunKind.DRONE, droneMidi = 69))
        store.accept(reading(cents = -2.0), firstSettled = true)
        store.newSession()
        now += 1_000
        store.setContext(alto.copy(nickname = "Horn B"))
        store.accept(reading(a4 = 442.0, cents = 7.5), firstSettled = true)
        val all = store.export(Scope.AllHistory(includeDroneRuns = true))
        assertEquals(3, all.size)
        assertEquals(listOf(RunKind.FREE, RunKind.DRONE, RunKind.FREE), all.map { it.kind })
        assertEquals(listOf("Reed A", "Reed A", ""), all.map { it.equipment })
        assertEquals(listOf("Morning", "Morning", ""), all.map { it.session })
        assertEquals(listOf(440.0, 440.0, 442.0), all.map { it.note.label.a4Hz })
        assertEquals("Horn B", all.last().note.label.nickname)
        assertEquals(4.2, all.first().note.meanCents, 1e-9)
        assertEquals(1.0, all.first().note.spreadCents, 1e-9)
        assertEquals(1L, all.first().note.attacks)
        assertEquals(2L, all.first().note.n)
        assertEquals(1_000L, all.last().startedAt - all.first().startedAt)
        assertEquals(2, store.export(Scope.AllHistory()).size)
        assertEquals(2, store.export(Scope.Session(first)).size)
        assertEquals(442.0, store.export(Scope.CurrentSession).single().note.label.a4Hz, 0.0)
        val snapshot = store.snapshot(Scope.AllHistory(), includeDroneRuns = false)
        assertEquals(2, snapshot.scopeSessions)
        assertEquals(all.first().startedAt, snapshot.since)
        assertEquals(2, snapshot.sessions.size)
        assertEquals(store.currentSessionId(), snapshot.currentSessionId)
        assertEquals(listOf(440.0, 442.0), snapshot.rows.map { it.label.a4Hz })
        assertEquals(snapshot.rows.map { it.n }, snapshot.history.map { it.n })
    }

    @Test
    fun `SES-03 clearing one note preserves other labels sessions and subsequent live capture`() = runBlocking {
        store.accept(reading(cents = 8.0))
        store.flushAndWait()
        val previous = store.currentSessionId()!!
        store.newSession()
        store.accept(reading(cents = 9.0))
        store.accept(reading(midi = 71, cents = 11.0))
        store.accept(reading(cents = 2.0, a4 = 442.0))
        store.flushAndWait()
        val label = RunLabel("alto-sax", -9, 440.0, "")
        store.clearNote(Scope.CurrentSession, label, 69)
        assertEquals(listOf(71, 69), store.summaries(Scope.CurrentSession).map { it.midiSounding })
        assertEquals(8.0, store.summaries(Scope.Session(previous)).single().meanCents, 0.0)
        assertEquals(3L, count("SELECT COUNT(*) FROM readings"))
        store.accept(reading(cents = -4.0))
        store.flushAndWait()
        val restored = store.summaries(Scope.CurrentSession).single { it.label == label && it.midiSounding == 69 }
        assertEquals(1L, restored.n)
        assertEquals(-4.0, restored.meanCents, 0.0)
        store.setContext(alto.copy(kind = RunKind.DRONE, droneMidi = 69))
        store.accept(reading(cents = 6.0))
        store.flushAndWait()
        store.clearNote(Scope.AllHistory(), label, 69)
        assertTrue(store.export(Scope.AllHistory()).none { it.note.label == label && it.note.midiSounding == 69 })
        assertEquals(6.0, store.export(Scope.AllHistory(true)).single { it.kind == RunKind.DRONE }.note.meanCents, 0.0)
    }

    @Test
    fun `SES-08 clearing a live note cannot restore its cached counts on the next flush`() = runBlocking {
        repeat(4) { store.accept(reading(cents = 10.0)) }
        store.accept(reading(midi = 71))
        store.flushAndWait()
        store.clearNote(Scope.CurrentSession, RunLabel("alto-sax", -9, 440.0, ""), 69)
        store.accept(reading(cents = -8.0), firstSettled = true)
        store.flushAndWait()
        val note = store.summaries(Scope.CurrentSession).single { it.midiSounding == 69 }
        assertEquals(1L, note.n)
        assertEquals(1L, note.attacks)
        assertEquals(-8.0, note.meanCents, 0.0)
        assertEquals(2L, count("SELECT COUNT(*) FROM readings"))
    }

    @Test
    fun `SES-07 a failed flush refuses clear and recovery can clear every retained row`() = runBlocking {
        store.accept(reading())
        store.flushAndWait()
        store.accept(reading(midi = 71))
        database.failWrites = true
        try {
            store.clearHistory()
            org.junit.Assert.fail("clear accepted a failed pending write")
        } catch (expected: HistoryWriteException) { assertTrue(expected.message!!.isNotBlank()) }
        database.failWrites = false
        assertEquals(1, store.sessions().size)
        assertEquals(1L, count("SELECT COUNT(*) FROM readings"))
        store.clearHistory()
        assertTrue(store.sessions().isEmpty())
        assertTrue(store.summaries(Scope.AllHistory(true)).isEmpty())
        assertEquals(0L, count("SELECT COUNT(*) FROM readings"))
        assertEquals(null, store.currentSessionId())
        store.accept(reading(midi = 74))
        store.flushAndWait()
        assertEquals(74, store.summaries(Scope.CurrentSession).single().midiSounding)
        assertEquals(1L, count("SELECT COUNT(*) FROM readings"))
    }

    /** A database whose writes can be made to fail, standing in for a full or broken disk. */
    private class FlakyDatabase(context: Context) : HistoryDatabase(context, DB_NAME) {
        @Volatile
        var failWrites = false

        override fun getWritableDatabase(): SQLiteDatabase {
            if (failWrites) throw SQLiteException("disk I/O error (planted)")
            return super.getWritableDatabase()
        }
    }

    private companion object {
        const val DB_NAME = "history-test.db"
    }
}
