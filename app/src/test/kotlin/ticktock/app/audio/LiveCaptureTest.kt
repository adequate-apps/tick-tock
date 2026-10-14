// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.InputFailure
import ticktock.core.contracts.InputInfo
import ticktock.core.contracts.InputSource
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.core.music.Pitch
import ticktock.engine.input.RecordingState
import java.util.concurrent.TimeUnit

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
        assertEquals(-23.0, state.readout.levelDb, 0.5)
        awaitUntil("counters") { capture.state.value.counters.hopsAnalysed == 8L }
        assertTrue(capture.state.value.lastHopNanos > 0)
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
    fun `silence after a note blanks the readout and the level falls to the floor`() {
        val input = FakeAudioInput(sampleRate = rate)
        capture.start(input)
        val sine = Sine(Pitch.hzFromMidi(69, 440.0), 0.1, rate)
        repeat(8) { input.push(sine.next(Hop.frames(rate))) }
        awaitUntil("note read") { capture.state.value.readout.note == "A4" }

        repeat(12) { input.push(FloatArray(Hop.frames(rate))) }

        awaitUntil("blank") { capture.state.value.readout.note == null }
        assertNull(capture.state.value.readout.cents)
        assertEquals(Readout.LEVEL_FLOOR_DB, capture.state.value.readout.levelDb, 0.0)
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
        assertEquals(0, input.openCount)
    }

    @Test
    fun `level in dBFS is floored and otherwise 20 log10 of the hop RMS`() {
        assertEquals(Readout.LEVEL_FLOOR_DB, Readout.levelDb(0.0), 0.0)
        assertEquals(Readout.LEVEL_FLOOR_DB, Readout.levelDb(1e-9), 0.0)
        assertEquals(-20.0, Readout.levelDb(0.1), 1e-9)
        assertEquals(0.0, Readout.levelDb(1.0), 1e-9)
    }

    private fun awaitUntil(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(5)
        }
    }
}
