// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.history

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.core.config.Spelling
import ticktock.core.contracts.GatedFeature
import ticktock.core.contracts.fakes.FakeEntitlement
import ticktock.core.detect.Reading
import ticktock.core.prefs.Prefs
import ticktock.data.StorageThread
import ticktock.data.db.HistoryDatabase
import ticktock.data.history.HistoryStore
import ticktock.data.history.RunContext
import ticktock.data.history.RunKind
import ticktock.data.history.Scope
import ticktock.data.history.SessionPurpose
import java.util.concurrent.Executors
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
class TableSessionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val storage = StorageThread()
    private val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var failWrites = false
    private val database = object : HistoryDatabase(context, "table-owner.db") {
        override fun getWritableDatabase(): SQLiteDatabase {
            if (failWrites) throw SQLiteException("write refused")
            return super.getWritableDatabase()
        }
    }
    private val alto = RunContext("alto-sax", -9)
    private val store = HistoryStore(database, storage, alto)
    private var prefs = Prefs(spelling = Spelling.SHARPS)
    private val access = FakeEntitlement(*GatedFeature.entries.toTypedArray())
    private val owner = TableSession(store, { prefs }, { prefs = it(prefs) },
        AllHistoryAccess(access), NamedSessionsAccess(access), TableExport(access), CoroutineScope(SupervisorJob() + ui))
    private var hop = 0L

    @After fun close() {
        owner.close()
        ui.close()
        storage.shutdown()
        database.close()
        context.deleteDatabase("table-owner.db")
    }

    private fun reading(midi: Int = 69, cents: Double = 4.0, a4: Double = 440.0) =
        Reading(hop++, midi, a4 * 2.0.pow((midi - 69 + cents / 100) / 12), cents, a4, 0)

    @Test fun `SES-03 POL-23 scope changes preserve original A4 and exclude drone runs by default`() = runBlocking(ui) {
        store.accept(reading(), firstSettled = true)
        store.flushAndWait()
        val first = store.currentSessionId()!!
        store.saveSession(first, "First", "Reed")
        store.newSession()
        store.accept(reading(midi = 71, cents = -8.0, a4 = 442.0), firstSettled = true)
        store.flushAndWait()
        owner.select(Scope.CurrentSession).join()
        assertEquals(71, owner.state.value.snapshot!!.rows.single().midiSounding)
        owner.select(Scope.AllHistory()).join()
        assertEquals(listOf(440.0, 442.0), owner.state.value.snapshot!!.rows.map { it.label.a4Hz })
        prefs = prefs.copy(a4Hz = 466.0)
        owner.refresh().join()
        assertEquals(listOf(4.0, -8.0), owner.state.value.snapshot!!.rows.map { it.meanCents })
        store.setContext(alto.copy(kind = RunKind.DRONE, droneMidi = 69))
        store.accept(reading(cents = 20.0))
        store.flushAndWait()
        owner.refresh().join()
        assertEquals(2L, owner.state.value.snapshot!!.rows.sumOf { it.n })
        owner.includeDroneRuns(true).join()
        assertTrue(prefs.includeDroneRuns)
        assertEquals(3L, owner.state.value.snapshot!!.rows.sumOf { it.n })
        owner.select(Scope.Session(first)).join()
        assertEquals(4.0, owner.state.value.snapshot!!.rows.single().meanCents, 0.0)
    }

    @Test fun `SES-08 visible table refreshes at a successful flush and retains its snapshot between readings`() = runBlocking(ui) {
        store.accept(reading())
        store.flushAndWait()
        owner.activate()
        val initial = withTimeout(5_000) { owner.state.first { it.snapshot?.rows?.sumOf { row -> row.n } == 1L } }.snapshot
        repeat(20) { store.accept(reading()) }
        delay(30)
        assertSame(initial, owner.state.value.snapshot)
        store.flushAndWait()
        withTimeout(5_000) { owner.state.first { it.snapshot?.rows?.sumOf { row -> row.n } == 21L } }
        owner.deactivate()
        val stopped = owner.state.value.snapshot
        store.accept(reading())
        store.flushAndWait()
        delay(30)
        assertSame(stopped, owner.state.value.snapshot)
    }

    @Test fun `EQP-02 named session actions retain metadata and CSV uses stored tuning labels`() = runBlocking(ui) {
        store.accept(reading(a4 = 442.0), firstSettled = true)
        store.flushAndWait()
        owner.refresh().join()
        val id = owner.state.value.snapshot!!.currentSessionId!!
        owner.save(id, "Study", "Reed, soft", SessionPurpose.EQUIPMENT, "New reed").join()
        assertEquals("Reed, soft", prefs.lastEquipment)
        val saved = owner.state.value.snapshot!!.sessions.single()
        assertEquals(SessionPurpose.EQUIPMENT, saved.purpose)
        assertEquals("New reed", saved.notes)
        val csv = owner.csv()!!.decodeToString()
        assertTrue(csv.contains("442.0,F#5,A4,1,1,4.0,0.0,free,\"Reed, soft\",Study,"))
        owner.newSession().join()
        assertTrue(owner.state.value.snapshot!!.rows.isEmpty())
        owner.resume(id).join()
        assertEquals(id, owner.state.value.snapshot!!.currentSessionId)
        assertEquals(1L, owner.state.value.snapshot!!.rows.single().n)
        owner.delete(id).join()
        assertTrue(owner.state.value.snapshot!!.sessions.isEmpty())
    }

    @Test fun `ENT-01 denial is confined to all history named sessions and CSV`() = runBlocking(ui) {
        store.accept(reading())
        store.flushAndWait()
        val id = store.currentSessionId()!!
        access.granted.clear()
        owner.select(Scope.AllHistory()).join()
        assertTrue(owner.state.value.gated)
        assertNull(owner.state.value.snapshot)
        owner.save(id, "Denied", "Reed", SessionPurpose.SHOP, "").join()
        assertEquals("", store.sessions().single().name)
        assertEquals("", prefs.lastEquipment)
        assertNull(owner.csv())
        owner.select(Scope.CurrentSession).join()
        assertFalse(owner.state.value.gated)
        assertEquals(1, owner.state.value.snapshot!!.rows.size)
        assertNull(owner.csv())
        access.granted += GatedFeature.EXPORT
        assertNotNull(owner.csv())
        assertTrue("denied historical comparison is absent", owner.state.value.snapshot!!.history.isEmpty())
        assertFalse(owner.allHistory.allowed)
        assertFalse(owner.namedSessions.allowed)
    }

    @Test fun `SES-07 failed save keeps its draft facts and error through a later successful read`() = runBlocking(ui) {
        store.accept(reading())
        store.flushAndWait()
        owner.refresh().join()
        val id = owner.state.value.snapshot!!.currentSessionId!!
        store.accept(reading())
        failWrites = true
        owner.save(id, "Study", "Reed", SessionPurpose.PRACTICE, "").join()
        assertEquals("Phone storage refused save", owner.state.value.error)
        assertFalse(owner.state.value.busy)
        assertEquals("", prefs.lastEquipment)
        failWrites = false
        owner.refresh().join()
        assertEquals("", owner.state.value.snapshot!!.sessions.single().name)
        assertEquals("Phone storage refused save", owner.state.value.error)
        owner.save(id, "Study", "Reed", SessionPurpose.PRACTICE, "").join()
        assertNull(owner.state.value.error)
        assertEquals("Study", owner.state.value.snapshot!!.sessions.single().name)
        assertEquals(2L, owner.state.value.snapshot!!.rows.single().n)
    }
}
