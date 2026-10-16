// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.app.AppGraph
import ticktock.core.contracts.HistorySink
import ticktock.core.contracts.SoundingNotes
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.Reading
import ticktock.core.detect.fakes.Sine
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import ticktock.data.StorageThread
import ticktock.data.db.HistoryDatabase
import ticktock.data.history.HistoryStore
import ticktock.data.history.Scope
import ticktock.data.prefs.AtomicPrefsFile
import ticktock.data.prefs.PrefsStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual store notifications, PCM analysis and stored history across publication races. */
@RunWith(RobolectricTestRunner::class)
class PrefsInstrumentBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `RNG-14 real prefs round trip cannot collapse the instrument boundary before PCM admission`() {
        val fixture = Fixture()
        val callbackEntered = CountDownLatch(1)
        val callbackResume = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        // This earlier listener holds the first publisher between publication and the bridge.
        fixture.store.addListener { prefs ->
            if (prefs.instrumentKey == Y && armed.getAndSet(false)) {
                callbackEntered.countDown()
                check(callbackResume.await(10, TimeUnit.SECONDS))
            }
        }
        fixture.store.addListener(fixture.session::settingsChanged)
        var publisher: Thread? = null
        val failure = AtomicReference<Throwable?>(null)
        try {
            fixture.establishNote()
            val before = fixture.savedReadings()
            val lastQueuedHop = fixture.holdOldBatch()
            armed.set(true)
            publisher = Thread {
                try { fixture.store.update { it.copy(instrumentKey = Y) } }
                catch (error: Throwable) { failure.set(error) }
            }.also { it.start() }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            fixture.store.update { it.copy(instrumentKey = X) }
            callbackResume.countDown()
            joinPublisher(publisher, failure)
            assertEquals(X, fixture.store.prefs.value.instrumentKey)
            fixture.releaseAnalysis.countDown()
            fixture.awaitHop(lastQueuedHop)
            assertEquals("a real X-Y-X publication must not admit the old queued batch", before, fixture.savedReadings())
            fixture.assertCleared()
            fixture.play(24)
            assertTrue("fresh PCM still reaches stored X history", fixture.savedReadings() > before)
            fixture.assertOnlyX()
        } finally {
            callbackResume.countDown()
            fixture.releaseAnalysis.countDown()
            publisher?.join(5_000)
            fixture.close()
        }
    }

    @Test fun `RNG-14 stale real prefs callback cannot strand fresh PCM behind a mismatched instrument`() {
        val fixture = Fixture()
        val callbackEntered = CountDownLatch(1)
        val callbackResume = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        // Capture the event at listener entry, then delay its delivery to the real session.
        fixture.store.addListener { prefs ->
            if (prefs.instrumentKey == Y && armed.getAndSet(false)) {
                callbackEntered.countDown()
                check(callbackResume.await(10, TimeUnit.SECONDS))
            }
            fixture.session.settingsChanged(prefs)
        }
        var publisher: Thread? = null
        val failure = AtomicReference<Throwable?>(null)
        try {
            fixture.establishNote()
            val before = fixture.savedReadings()
            val lastQueuedHop = fixture.holdOldBatch()
            armed.set(true)
            publisher = Thread {
                try { fixture.store.update { it.copy(instrumentKey = Y) } }
                catch (error: Throwable) { failure.set(error) }
            }.also { it.start() }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            fixture.store.update { it.copy(instrumentKey = X) }
            callbackResume.countDown()
            joinPublisher(publisher, failure)
            fixture.releaseAnalysis.countDown()
            fixture.awaitHop(lastQueuedHop)
            fixture.play(24)
            assertEquals(X, fixture.store.prefs.value.instrumentKey)
            assertEquals(69, fixture.session.state.value.readout.midi)
            assertTrue("fresh X PCM must remain writable after an older Y callback", fixture.savedReadings() > before)
            fixture.assertOnlyX()
        } finally {
            callbackResume.countDown()
            fixture.releaseAnalysis.countDown()
            publisher?.join(5_000)
            fixture.close()
        }
    }

    private fun joinPublisher(publisher: Thread, failure: AtomicReference<Throwable?>) {
        publisher.join(5_000)
        assertFalse("prefs callback completed", publisher.isAlive)
        failure.get()?.let { throw AssertionError("prefs publisher failed", it) }
    }

    private inner class Fixture {
        private val storage = StorageThread()
        private val context = RuntimeEnvironment.getApplication()
        private val path = temporary.newFile().apply { writeText(PrefsSchema.write(Prefs(instrumentKey = X))) }
        val store = PrefsStore(AtomicPrefsFile(path), storage).also { it.load() }
        private val database = HistoryDatabase(context, null)
        private val history = HistoryStore(database, storage, AppGraph.runContextOf(store.prefs.value))
        private val input = FakeAudioInput(48_000)
        private val tone = Sine(440.0, 0.1, 48_000)
        private val intercept = AtomicBoolean(false)
        private val analysisEntered = CountDownLatch(1)
        val releaseAnalysis = CountDownLatch(1)
        private val sounding = object : SoundingNotes {
            override fun current(): IntArray {
                if (intercept.getAndSet(false)) {
                    analysisEntered.countDown()
                    check(releaseAnalysis.await(10, TimeUnit.SECONDS))
                }
                return intArrayOf()
            }
        }
        // Same admission/label boundary as AppGraph; no manufactured detector readings enter it.
        private val sink = object : HistorySink {
            override val rejected: Int get() = history.rejected
            override fun accept(reading: Reading, firstSettled: Boolean) {
                val prefs = store.prefs.value
                if (!session.acceptsInstrument(prefs.instrumentKey)) return
                history.setContext(AppGraph.runContextOf(prefs))
                history.accept(reading, firstSettled)
            }
        }
        val session: TuneSession = TuneSession(context, store.prefs.value, sink,
            makeInput = { input }, micAllowed = { true }, sounding = sounding)

        fun establishNote() {
            history.start()
            session.resume()
            play(24)
            assertEquals(69, session.state.value.readout.midi)
            assertTrue(savedReadings() > 0)
        }

        fun play(hops: Int) {
            repeat(hops) {
                val next = session.level.value.hop + 1
                assertTrue(input.push(tone.next(Hop.frames(48_000))))
                awaitHop(next)
            }
        }

        fun holdOldBatch(): Long {
            val before = session.level.value.hop
            intercept.set(true)
            assertTrue(input.push(tone.next(Hop.frames(48_000))))
            assertTrue("old analysis is in flight", analysisEntered.await(5, TimeUnit.SECONDS))
            assertTrue(input.push(tone.next(8 * Hop.frames(48_000))))
            return before + 9
        }

        fun awaitHop(hop: Long) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (session.level.value.hop < hop) {
                check(System.nanoTime() < deadline) { "analysis did not reach hop $hop" }
                Thread.sleep(2)
            }
        }

        fun savedReadings(): Long {
            assertTrue(history.flushAndWait())
            return runBlocking { history.summaries(Scope.CurrentSession) }.sumOf { it.n }
        }

        fun assertOnlyX() {
            assertTrue(input.isOpen)
            val rows = runBlocking { history.summaries(Scope.CurrentSession) }
            assertEquals(setOf(X), rows.map { it.label.instrumentKey }.toSet())
            assertTrue(rows.all { it.label.transposition == -9 })
        }

        fun assertCleared() {
            assertNull(session.state.value.readout.midi)
            assertNull(session.lastNote.value)
            assertEquals(0, session.trace.value.attacks)
            assertTrue(session.trace.value.cents.all { it.isNaN() })
        }

        fun close() {
            releaseAnalysis.countDown()
            session.close()
            history.stop()
            storage.shutdown()
            database.close()
        }
    }

    companion object {
        private const val X = "eb_alto"
        private const val Y = "flute_c"
    }
}
