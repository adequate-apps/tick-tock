// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.app.AppGraph
import ticktock.app.audio.RangeHint
import ticktock.app.audio.RangeHintDirection
import ticktock.core.contracts.HistorySink
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.Reading
import ticktock.core.detect.fakes.Sine
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import ticktock.core.prefs.Theme
import ticktock.core.ranges.UserRangeOverride
import ticktock.core.ranges.WrittenRange
import ticktock.data.StorageThread
import ticktock.data.db.HistoryDatabase
import ticktock.data.history.HistoryStore
import ticktock.data.prefs.AtomicPrefsFile
import ticktock.data.prefs.PrefsPublication
import ticktock.data.prefs.PrefsStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/** Real source publications and accepted PCM distinguish range changes from unrelated revision gaps. */
@RunWith(RobolectricTestRunner::class)
class PrefsRangeHintBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `HNT-07 out of order range round trip clears old evidence and preserves fresh final-range evidence`() {
        val fixture = Fixture()
        val delayB = CallbackGate { isWide(it.prefs) }
        fixture.connect(delayB::beforeDelivery)
        var publisher: Publisher? = null
        try {
            fixture.start()
            fixture.trigger()
            val before = fixture.rows()
            val original = fixture.store.publication.value
            delayB.arm()
            publisher = Publisher { fixture.setWide(true) }
            delayB.awaitEntry()
            fixture.setWide(false)

            val returned = fixture.store.publication.value
            assertEquals(original.prefs, returned.prefs)
            assertEquals("this is a range-only round trip", original.instrumentRevision, returned.instrumentRevision)
            assertTrue(returned.revision > original.revision)
            assertEquals("silent settings changes preserve history", before, fixture.rows())
            assertNull("the final A callback must discard old evidence even before delayed B arrives",
                fixture.session.rangeHint.value)

            // Keep B delayed while new accepted PCM establishes a new A hint.
            fixture.freshHint()
            val fresh = fixture.hint()
            fixture.assertPrefix(before)
            delayB.release()
            publisher.awaitCompletion()
            assertEquals("late B cannot clear fresh final-A evidence", fresh, fixture.session.rangeHint.value)
            fixture.tone(44)
            fixture.hint()
            fixture.assertPrefix(before)
        } finally {
            delayB.release()
            publisher?.joinForCleanup()
            fixture.close()
        }
    }

    @Test fun `HNT-02 unrelated out of order preference gaps preserve accepted stray evidence`() {
        val fixture = Fixture()
        val delayTheme = CallbackGate { it.prefs.theme == Theme.DARK }
        fixture.connect(delayTheme::beforeDelivery)
        var publisher: Publisher? = null
        try {
            fixture.start()
            fixture.tone(40)
            fixture.rest()
            fixture.tone(42)
            fixture.rest()
            assertNull(fixture.session.rangeHint.value)
            val before = fixture.rows()
            val original = fixture.store.publication.value
            delayTheme.arm()
            publisher = Publisher { fixture.store.update { it.copy(theme = Theme.DARK) } }
            delayTheme.awaitEntry()
            fixture.store.update { it.copy(theme = original.prefs.theme) }
            delayTheme.release()
            publisher.awaitCompletion()

            val returned = fixture.store.publication.value
            assertEquals(original.prefs, returned.prefs)
            assertEquals(original.instrumentRevision, returned.instrumentRevision)
            assertTrue("the callback gap is real", returned.revision >= original.revision + 2)
            assertEquals(before, fixture.rows())
            fixture.tone(44)
            val hint = fixture.hint()
            fixture.assertPrefix(before)

            // A different instrument's imported override does not change alto's judgment either.
            fixture.store.update { prefs ->
                prefs.copy(personalConfig = prefs.personalConfig.copy(
                    rangeOverrides = prefs.personalConfig.rangeOverrides + ("bb_tenor" to WIDE)))
            }
            assertEquals("unselected ranges preserve the active hint", hint, fixture.session.rangeHint.value)
            assertTrue(hint.matches(fixture.store.publication.value))
        } finally {
            delayTheme.release()
            publisher?.joinForCleanup()
            fixture.close()
        }
    }

    @Test fun `HNT-07 sequential silent range round trip resets evidence without resetting capture`() {
        val fixture = Fixture()
        fixture.connect()
        try {
            fixture.start()
            fixture.trigger()
            val before = fixture.rows()
            val original = fixture.store.publication.value
            val lastNote = fixture.session.lastNote.value
            assertNotNull("the released note supplies a preservation check", lastNote)

            fixture.setWide(true)
            assertNull("new range removes the old hint without PCM", fixture.session.rangeHint.value)
            fixture.setWide(false)
            assertNull("returning to the original range does not restore old evidence", fixture.session.rangeHint.value)
            assertEquals(original.prefs, fixture.store.publication.value.prefs)
            assertEquals(original.instrumentRevision, fixture.store.publication.value.instrumentRevision)
            assertEquals(before, fixture.rows())
            assertSame(lastNote, fixture.session.lastNote.value)

            fixture.tone(40)
            assertNull("one fresh stray is insufficient", fixture.session.rangeHint.value)
            assertSame("a range-only update must not clear the completed note", lastNote, fixture.session.lastNote.value)
            fixture.rest()
            fixture.tone(42)
            assertNull("two fresh strays are insufficient", fixture.session.rangeHint.value)
            fixture.rest()
            fixture.tone(44)
            fixture.hint()
            fixture.assertPrefix(before)
        } finally {
            fixture.close()
        }
    }

    @Test fun `HNT-07 authoritative range round trip masks old hint before either listener arrives`() {
        val fixture = Fixture()
        val delayB = CallbackGate { isWide(it.prefs) }
        val delayA = CallbackGate { !isWide(it.prefs) }
        fixture.connect { publication ->
            delayB.beforeDelivery(publication)
            delayA.beforeDelivery(publication)
        }
        var publisherB: Publisher? = null
        var publisherA: Publisher? = null
        try {
            fixture.start()
            fixture.trigger()
            val old = fixture.hint()
            val original = fixture.store.publication.value
            delayB.arm()
            publisherB = Publisher { fixture.setWide(true) }
            delayB.awaitEntry()
            assertFalse("a changed effective range already masks the old hint", old.matches(fixture.store.publication.value))

            delayA.arm()
            publisherA = Publisher { fixture.setWide(false) }
            delayA.awaitEntry()
            assertEquals(original.prefs, fixture.store.publication.value.prefs)
            assertEquals(original.instrumentRevision, fixture.store.publication.value.instrumentRevision)
            assertSame("neither callback has reached capture", old, fixture.session.rangeHint.value)
            assertFalse("the source round trip must mask old evidence before listener delivery",
                old.matches(fixture.store.publication.value))

            delayA.release()
            publisherA.awaitCompletion()
            assertNull(fixture.session.rangeHint.value)
            delayB.release()
            publisherB.awaitCompletion()
            fixture.freshHint()
        } finally {
            delayA.release()
            delayB.release()
            publisherA?.joinForCleanup()
            publisherB?.joinForCleanup()
            fixture.close()
        }
    }

    /** Holds a captured real event after its source publication and before its session callback. */
    private class CallbackGate(private val selected: (PrefsPublication) -> Boolean) {
        private val armed = AtomicBoolean(false)
        private val entered = CountDownLatch(1)
        private val resume = CountDownLatch(1)

        fun arm() { armed.set(true) }
        fun beforeDelivery(publication: PrefsPublication) {
            if (selected(publication) && armed.getAndSet(false)) {
                entered.countDown()
                check(resume.await(30, TimeUnit.SECONDS)) { "delayed prefs callback was not released" }
            }
        }
        fun awaitEntry() { assertTrue("source event reached its callback gate", entered.await(5, TimeUnit.SECONDS)) }
        fun release() { resume.countDown() }
    }

    private class Publisher(action: () -> Unit) {
        private val failure = AtomicReference<Throwable?>(null)
        private val thread = Thread {
            try { action() } catch (error: Throwable) { failure.set(error) }
        }.also { it.start() }

        fun awaitCompletion() {
            thread.join(5_000)
            assertFalse("publisher completed", thread.isAlive)
            failure.get()?.let { throw AssertionError("prefs publisher failed", it) }
        }
        fun joinForCleanup() { thread.join(5_000) }
    }

    private data class Stored(
        val hop: Long, val midi: Int, val hz: Double, val cents: Double,
        val instrument: String, val transposition: Int, val a4: Double,
    )

    private inner class Fixture {
        private val storage = StorageThread()
        private val context = RuntimeEnvironment.getApplication()
        private val path = temporary.newFile().apply { writeText(PrefsSchema.write(Prefs(instrumentKey = ALTO))) }
        val store = PrefsStore(AtomicPrefsFile(path), storage).also { it.load() }
        private val initial = store.publication.value
        private val originalOverride = initial.prefs.personalConfig.rangeOverrides[ALTO]
        private val database = HistoryDatabase(context, null)
        private val history = HistoryStore(database, storage, AppGraph.runContextOf(initial.prefs))
        private val input = FakeAudioInput(RATE)
        private var deliveredHops = 0L
        private val sink = object : HistorySink {
            override val rejected: Int get() = history.rejected
            override fun accept(reading: Reading, firstSettled: Boolean) {
                val publication = store.publication.value
                if (!session.acceptsInstrument(publication)) return
                history.setContext(AppGraph.runContextOf(publication.prefs))
                history.accept(reading, firstSettled)
            }
        }
        val session: TuneSession = TuneSession(context, initial.prefs, sink,
            makeInput = { input }, micAllowed = { true }, initialPublication = initial)

        fun connect(beforeDelivery: (PrefsPublication) -> Unit = {}) {
            store.addPublicationListener { publication ->
                beforeDelivery(publication)
                session.settingsChanged(publication)
            }
        }

        fun start() { history.start(); session.resume() }

        fun setWide(wide: Boolean) {
            store.update { prefs ->
                val overrides = prefs.personalConfig.rangeOverrides
                val selected = if (wide) WIDE else originalOverride
                prefs.copy(personalConfig = prefs.personalConfig.copy(
                    rangeOverrides = if (selected == null) overrides - ALTO else overrides + (ALTO to selected)))
            }
        }

        fun trigger() {
            freshHint()
            rest()
            hint()
        }

        fun freshHint() {
            tone(40)
            assertNull("one fresh stray is insufficient", session.rangeHint.value)
            rest()
            tone(42)
            assertNull("two fresh strays are insufficient", session.rangeHint.value)
            rest()
            tone(44)
            hint()
        }

        fun tone(midi: Int) {
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val sine = Sine(hz, 0.1, RATE)
            repeat(24) { deliver(sine.next(Hop.frames(RATE))) }
            runBlocking { withTimeout(5_000) { session.state.first { it.readout.midi == midi && it.readout.cents == 0 } } }
            val shown = session.state.value.readout
            assertEquals("independent pitch for accepted PCM", midi, shown.midi)
            assertEquals(0, shown.cents)
            assertEquals(hz, shown.hz!!, hz * (2.0.pow(0.5 / 1200) - 1.0) + 0.1)
            assertStream()
        }

        fun rest() { repeat(12) { deliver(FloatArray(Hop.frames(RATE))) } }

        private fun deliver(samples: FloatArray) {
            assertTrue("the original input accepts PCM", input.push(samples))
            deliveredHops++
            runBlocking { withTimeout(5_000) { session.level.first { it.hop >= deliveredHops - 1 } } }
            assertEquals("no input hops were lost", deliveredHops - 1, session.level.value.hop)
        }

        fun hint(): RangeHint {
            val hint = session.rangeHint.value
            assertNotNull("three accepted low strays produce a hint", hint)
            return hint!!.also {
                assertEquals(ALTO, it.instrumentKey)
                assertEquals(RangeHintDirection.BELOW, it.direction)
                assertTrue("the hint belongs to the authoritative range", it.matches(store.publication.value))
            }
        }

        fun rows(): List<Stored> {
            assertTrue(history.flushAndWait())
            val result = storage.call {
                database.readableDatabase.rawQuery("""SELECT h.hop, h.midi_sounding, h.hz, h.cents,
                    r.instrument_key, r.transposition, r.a4_hz
                    FROM readings h JOIN runs r ON h.run_id = r.id ORDER BY h.rowid""", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(Stored(cursor.getLong(0), cursor.getInt(1), cursor.getDouble(2),
                            cursor.getDouble(3), cursor.getString(4), cursor.getInt(5), cursor.getDouble(6)))
                    }
                }
            }
            assertTrue("real accepted readings reached the database", result.isNotEmpty())
            assertEquals("accepted readings are stored once", result.size, result.map { it.hop }.distinct().size)
            assertTrue(result.all { it.instrument == ALTO && it.transposition == -9 && it.a4 == 440.0 })
            assertStream()
            return result
        }

        fun assertPrefix(before: List<Stored>) {
            val after = rows()
            assertTrue("fresh PCM still appends history", after.size > before.size)
            assertEquals("range changes preserve all earlier readings", before, after.take(before.size))
            assertEquals(setOf(40, 42, 44), after.map { it.midi }.toSet())
        }

        private fun assertStream() {
            assertTrue(input.isOpen)
            assertEquals(1, input.openCount)
            assertEquals("range updates do not reopen input", 1, input.startCalls)
            assertEquals("range updates do not stop input", 0, input.stopCalls)
            assertEquals(0, history.rejected)
            assertEquals(0L, session.diagnostics.value.counters.droppedHops)
            assertEquals(0L, session.diagnostics.value.counters.analysisErrors)
        }

        fun close() {
            session.close()
            history.stop()
            store.flushAndWait()
            storage.shutdown()
            database.close()
        }
    }

    companion object {
        private const val ALTO = "eb_alto"
        private const val RATE = 48_000
        private val WIDE = UserRangeOverride(WrittenRange(45, 101))
        private fun isWide(prefs: Prefs): Boolean = prefs.personalConfig.rangeOverrides[ALTO] == WIDE
    }
}
