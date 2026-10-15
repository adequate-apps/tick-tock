// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.InputFailure
import ticktock.core.contracts.InputInfo
import ticktock.core.contracts.InputSource
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.contracts.fakes.FakeHistorySink
import ticktock.core.detect.Attack
import ticktock.core.detect.Decision
import ticktock.core.detect.HopClass
import ticktock.core.detect.Reading
import ticktock.core.detect.Release
import ticktock.core.detect.ReleaseCause
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.core.music.Pitch
import ticktock.engine.input.RecordingState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The session object folds decisions, failures and the platform report into one state. */
class LiveCaptureTest {
    private val rate = 48_000
    private val capture = LiveCapture(promote = {})

    @After
    fun stop() {
        capture.stop()
    }

    @Test
    fun `a held note becomes a readout with the note, whole cents, Hz and level`() {
        val input = FakeAudioInput(sampleRate = rate)
        capture.start(input)
        assertTrue(capture.state.value.isListening)
        assertEquals(InputInfo(rate, "Fake microphone", InputSource.FAKE), capture.state.value.info)

        val sine = Sine(Pitch.hzFromMidi(69, 440.0), 0.1, rate)
        repeat(8) { input.push(sine.next(Hop.frames(rate))) }

        awaitUntil("note read") { capture.state.value.readout.note == "A4" }
        val state = capture.state.value
        assertEquals(0, state.readout.cents)
        assertEquals(440.0, state.readout.hz ?: 0.0, 0.5)
        awaitUntil("level") { capture.level.value.hop >= 0 }
        assertEquals(-23.0, capture.level.value.db, 0.5)

        // The diagnostics are sampled about once a second, never per hop.
        assertEquals(CaptureDiagnostics(), capture.diagnostics.value)
        repeat(LiveCapture.DIAGNOSTICS_HOPS) { input.push(sine.next(Hop.frames(rate))) }
        awaitUntil("counters") { capture.diagnostics.value.counters.hopsAnalysed >= LiveCapture.DIAGNOSTICS_HOPS }
        assertTrue(capture.diagnostics.value.lastHopNanos > 0)
    }

    @Test
    fun `saved readings reach history with the first one after each attack marked`() {
        val sink = FakeHistorySink()
        val stored = LiveCapture(promote = {}, history = sink)
        val input = FakeAudioInput(sampleRate = rate)
        stored.start(input)
        val sine = Sine(Pitch.hzFromMidi(69, 440.0), 0.1, rate)
        repeat(20) { input.push(sine.next(Hop.frames(rate))) }
        awaitUntil("readings stored") { synchronized(sink) { sink.accepted.size } >= 5 }
        stored.stop()

        synchronized(sink) {
            assertTrue(sink.accepted.all { it.midiSounding == 69 })
            assertEquals("one attack, so one first-settled reading", 1, sink.firstSettled.count { it })
            assertTrue(sink.firstSettled.first())
        }
    }

    @Test
    fun `POL-31 a held note moves the state a few times, the level every hop and the diagnostics about once a second`() {
        val input = FakeAudioInput(sampleRate = rate)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val states = AtomicInteger()
        val levels = AtomicInteger()
        val diagnostics = AtomicInteger()
        scope.launch { capture.state.collect { states.incrementAndGet() } }
        scope.launch { capture.level.collect { levels.incrementAndGet() } }
        scope.launch { capture.diagnostics.collect { diagnostics.incrementAndGet() } }
        capture.start(input)
        val afterStart = states.get()

        val sine = Sine(Pitch.hzFromMidi(69, 440.0), 0.1, rate)
        val hops = 3 * LiveCapture.DIAGNOSTICS_HOPS
        val levelsBefore = levels.get()
        for (i in 1..hops) {
            input.push(sine.next(Hop.frames(rate)))
            // One hop at a time, so every hop is its own batch and its own level.
            awaitUntil("hop $i analysed") { levels.get() - levelsBefore >= i }
        }
        awaitUntil("note read") { capture.state.value.readout.note == "A4" }

        assertTrue("the readout moved ${states.get() - afterStart} times for $hops hops of one note", states.get() - afterStart in 1..4)
        assertEquals(hops, levels.get() - levelsBefore)
        assertEquals(3, diagnostics.get() - 1)  // the initial value, then one sample per DIAGNOSTICS_HOPS
        assertEquals(hops.toLong(), capture.diagnostics.value.counters.hopsAnalysed)
        scope.cancel()
    }

    @Test
    fun `POL-19 the capture's A4 is the detector's, so a 442 Hz sine reads A4 at 0 cents at A4 = 442 and +8 at 440`() {
        val at442 = LiveCapture(a4Hz = 442.0, promote = {})
        val input = FakeAudioInput(sampleRate = rate)
        at442.start(input)
        val sine = Sine(442.0, 0.1, rate)
        repeat(8) { input.push(sine.next(Hop.frames(rate))) }
        awaitUntil("note read at 442") { at442.state.value.readout.note == "A4" }
        assertEquals(0, at442.state.value.readout.cents)
        assertEquals(442.0, at442.state.value.readout.hz ?: 0.0, 0.5)
        at442.stop()

        capture.start(input)
        repeat(8) { input.push(sine.next(Hop.frames(rate))) }
        awaitUntil("note read at 440") { capture.state.value.readout.note == "A4" && capture.state.value.readout.cents != null }
        assertEquals(8, capture.state.value.readout.cents)
    }

    @Test
    fun `POL-19 the first reading after an A4 request uses the new reference`() {
        val analyse = CountDownLatch(1)
        val sink = FakeHistorySink()
        val stored = LiveCapture(promote = { analyse.await() }, history = sink)
        val input = FakeAudioInput(sampleRate = rate)
        try {
            stored.start(input)
            stored.requestA4(442.0)
            val sine = Sine(442.0, 0.1, rate)
            repeat(8) { input.push(sine.next(Hop.frames(rate))) }
            analyse.countDown()
            awaitUntil("first reading stored") { synchronized(sink) { sink.accepted.isNotEmpty() } }
            synchronized(sink) {
                assertEquals(0.0, sink.accepted.first().cents, 0.5)
            }
        } finally {
            analyse.countDown()
            stored.stop()
        }
    }

    @Test
    fun `silence after a note blanks the readout and the level falls to the floor`() {
        val input = FakeAudioInput(sampleRate = rate)
        capture.start(input)
        val sine = Sine(Pitch.hzFromMidi(69, 440.0), 0.1, rate)
        repeat(8) { input.push(sine.next(Hop.frames(rate))) }
        awaitUntil("note read") { capture.state.value.readout.note == "A4" }

        repeat(12) { input.push(FloatArray(Hop.frames(rate))) }

        awaitUntil("blank") { capture.state.value.readout.note == null }
        assertNull(capture.state.value.readout.cents)
        awaitUntil("floor") { capture.level.value.db == Level.LEVEL_FLOOR_DB }
    }

    @Test
    fun `a failed start, a stream that stops and one that resumes all land in the state`() {
        val input = FakeAudioInput(sampleRate = rate)
        input.failNextStart = InputFailure.Busy("Built-in microphone")
        capture.start(input)
        assertEquals(InputFailure.Busy("Built-in microphone"), capture.state.value.failure)
        assertFalse(capture.state.value.isListening)

        capture.start(input)
        assertTrue(capture.state.value.isListening)
        capture.onDeviceAbsent(InputFailure.Disconnected("USB interface"))
        capture.onStopped(InputFailure.Disconnected("Built-in microphone"))
        assertEquals(InputFailure.Disconnected("Built-in microphone"), capture.state.value.failure)
        assertNull(capture.state.value.info)

        capture.onResumed(InputInfo(rate, "USB interface", InputSource.UNPROCESSED))
        assertTrue(capture.state.value.isListening)
        assertEquals("USB interface", capture.state.value.info?.deviceName)
        assertEquals(InputFailure.Disconnected("USB interface"), capture.state.value.absentDevice)

        val report = RecordingState(InputSource.UNPROCESSED, emptyList(), silenced = true, reported = true)
        capture.recordingReported(report)
        assertEquals(report, capture.state.value.recording)

        capture.stop()
        assertEquals(CaptureState(), capture.state.value)
        assertEquals(Level(), capture.level.value)
        assertEquals(CaptureDiagnostics(), capture.diagnostics.value)
        assertEquals(0, input.openCount)
    }

    @Test
    fun `level in dBFS is floored and otherwise 20 log10 of the hop RMS`() {
        assertEquals(Level.LEVEL_FLOOR_DB, Level.db(0.0), 0.0)
        assertEquals(Level.LEVEL_FLOOR_DB, Level.db(1e-9), 0.0)
        assertEquals(-20.0, Level.db(0.1), 1e-9)
        assertEquals(0.0, Level.db(1.0), 1e-9)
    }

    @Test
    fun `the readout keeps the tenth of a hertz it shows and nothing finer`() {
        assertEquals(440.0, Readout.shownHz(440.04), 0.0)
        assertEquals(440.1, Readout.shownHz(440.05), 0.0)
        assertEquals(27.5, Readout.shownHz(27.4999), 0.0)
    }

    @Test
    fun `EAR-02 last note keeps the held mean, settled attack, duration, trace and serial`() {
        val first = Reading(10, 69, 440.5, 2.0, 440.0, 0)
        val second = first.copy(hop = 11, hz = 441.5, cents = 4.0)
        recordNotes(
            Decision(10, HopClass.VOTE, level = 0.1, incumbent = 69,
                attack = Attack(10, 69, 2.0, 0), display = first),
            Decision(11, HopClass.VOTE, level = 0.1, incumbent = 69,
                display = second, saved = first, firstSettled = true),
            Decision(12, HopClass.REJECT, level = 0.0, incumbent = 69),
        )
        assertNull(capture.lastNote.value)
        recordNotes(Decision(14, HopClass.REJECT, level = 0.0, incumbent = null,
            release = Release(14, 69, 0, ReleaseCause.SILENCE)))
        val note = requireNotNull(capture.lastNote.value)
        assertEquals(69, note.midi)
        assertEquals(3.0, note.meanCents, 0.0)
        assertEquals(441.0, note.meanHz, 0.0)
        assertEquals(2.0, requireNotNull(note.settledCents), 0.0)
        assertEquals(4 * Hop.SECONDS, note.seconds, 0.0)
        assertArrayEquals(floatArrayOf(2f, 4f), note.points, 0f)
        assertEquals(1, note.serial)

        // An attack with no readings leaves the last completed note intact.
        recordNotes(
            Decision(20, HopClass.VOTE, level = 0.1, incumbent = 70, attack = Attack(20, 70, 0.0, 0)),
            Decision(21, HopClass.REJECT, level = 0.0, incumbent = null,
                release = Release(21, 70, 0, ReleaseCause.SILENCE)),
        )
        assertTrue(note === capture.lastNote.value)
        recordNotes(
            Decision(30, HopClass.VOTE, level = 0.1, incumbent = 69,
                attack = Attack(30, 69, -2.0, 0), display = first.copy(hop = 30, cents = -2.0)),
            Decision(35, HopClass.REJECT, level = 0.0, incumbent = null,
                release = Release(35, 69, 0, ReleaseCause.SILENCE)),
        )
        val next = requireNotNull(capture.lastNote.value)
        assertEquals(2, next.serial)
        assertEquals(-2.0, next.meanCents, 0.0)
        assertNull(next.settledCents)
        assertEquals(5 * Hop.SECONDS, next.seconds, 0.0)
        assertArrayEquals(floatArrayOf(-2f), next.points, 0f)
        assertArrayEquals(floatArrayOf(2f, 4f), note.points, 0f)
    }

    @Test
    fun `EAR-02 a new attack releases the previous note before tracking its own readings`() {
        val first = Reading(10, 69, 440.0, 0.0, 440.0, 0)
        recordNotes(
            Decision(10, HopClass.VOTE, level = 0.1, incumbent = 69,
                attack = Attack(10, 69, 0.0, 0), display = first),
            Decision(20, HopClass.VOTE, level = 0.1, incumbent = 71,
                release = Release(20, 69, 0, ReleaseCause.NOTE_CHANGE),
                attack = Attack(20, 71, 1.0, 0), display = first.copy(hop = 20, midiSounding = 71, cents = 1.0)),
        )
        val firstNote = requireNotNull(capture.lastNote.value)
        assertEquals(69, firstNote.midi)
        assertEquals(1, firstNote.serial)
        assertEquals(10 * Hop.SECONDS, firstNote.seconds, 0.0)
        recordNotes(Decision(25, HopClass.REJECT, level = 0.0, incumbent = null,
            release = Release(25, 71, 0, ReleaseCause.SILENCE)))
        val secondNote = requireNotNull(capture.lastNote.value)
        assertEquals(71, secondNote.midi)
        assertEquals(2, secondNote.serial)
        assertEquals(1.0, secondNote.meanCents, 0.0)
    }

    /** Script the detector edges to exercise tracking independently of pitch estimation and scheduling. */
    private fun recordNotes(vararg decisions: Decision) {
        val method = LiveCapture::class.java.getDeclaredMethod("recordNotes", List::class.java)
        method.isAccessible = true
        method.invoke(capture, decisions.toList())
    }

    private fun awaitUntil(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(5)
        }
    }
}
