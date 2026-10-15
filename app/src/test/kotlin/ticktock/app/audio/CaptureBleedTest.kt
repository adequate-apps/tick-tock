// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.InputFailure
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.contracts.fakes.FakeHistorySink
import ticktock.core.contracts.fakes.FakeSoundingNotes
import ticktock.core.detect.Hop
import ticktock.core.detect.Decision
import ticktock.core.detect.HopClass
import ticktock.core.detect.fakes.Sine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CaptureBleedTest {
    @Test
    fun `a disconnect wins over an in-flight duck command`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gain = AtomicReference(1f)
        val capture = LiveCapture(promote = {}, onDuck = { value ->
            if (value < 1f) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            gain.set(value)
        })
        capture.start(FakeAudioInput(sampleRate = RATE))
        val failure = InputFailure.Disconnected("USB microphone")
        val method = LiveCapture::class.java.getDeclaredMethod("onDecisions", List::class.java).apply { isAccessible = true }
        val analysis = Thread { method.invoke(capture, listOf(Decision(0, HopClass.SKIP, level = 0.1, incumbent = null, duck = 0.3f))) }
        val stopped = Thread { capture.onStopped(failure) }
        try {
            analysis.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            stopped.start()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (capture.state.value.failure != failure && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(failure, capture.state.value.failure)
            release.countDown()
            analysis.join(5_000)
            stopped.join(5_000)
            assertTrue(!analysis.isAlive && !stopped.isAlive)
            assertEquals(1f, gain.get())
        } finally {
            release.countDown()
            analysis.join(5_000)
            stopped.join(5_000)
            capture.stop()
        }
    }

    @Test
    fun `POL-24 own drone leakage is ducked and excluded and capture stop restores gain`() {
        val gain = AtomicReference(1f)
        val sink = FakeHistorySink()
        val notes = FakeSoundingNotes(69)
        val capture = LiveCapture(promote = {}, history = sink, sounding = notes, onDuck = gain::set)
        val input = FakeAudioInput(sampleRate = RATE)
        capture.start(input)
        try {
            val sine = Sine(440.0, 0.2, RATE)
            repeat(25) { hop(capture, input, sine.next(Hop.frames(RATE)).map { it * gain.get() }.toFloatArray()) }
            assertEquals(0.3f, gain.get())
            assertTrue(sink.accepted.isEmpty())
            capture.onStopped(InputFailure.Disconnected("USB microphone"))
            assertEquals(1f, gain.get())
            gain.set(0.3f)
        } finally { capture.stop() }
        assertEquals(1f, gain.get())
    }

    @Test
    fun `POL-27 a player holding unison through the duck still reaches history`() {
        val gain = AtomicReference(1f)
        val sink = FakeHistorySink()
        val capture = LiveCapture(promote = {}, history = sink, sounding = FakeSoundingNotes(69), onDuck = gain::set)
        val input = FakeAudioInput(sampleRate = RATE)
        capture.start(input)
        try {
            val sine = Sine(440.0, 0.2, RATE)
            repeat(30) { hop(capture, input, sine.next(Hop.frames(RATE))) }
            assertEquals(0.3f, gain.get())
            assertTrue(sink.accepted.isNotEmpty())
            assertTrue(sink.accepted.all { it.midiSounding == 69 })
        } finally { capture.stop() }
        assertEquals(1f, gain.get())
    }

    @Test
    fun `POL-28 capture restart forgets unison before hearing only the drone`() {
        val gain = AtomicReference(1f)
        val sink = FakeHistorySink()
        val capture = LiveCapture(promote = {}, history = sink, sounding = FakeSoundingNotes(69), onDuck = gain::set)
        val input = FakeAudioInput(sampleRate = RATE)
        capture.start(input)
        try {
            val sine = Sine(440.0, 0.2, RATE)
            repeat(30) { hop(capture, input, sine.next(Hop.frames(RATE))) }
            val before = sink.accepted.size
            assertTrue(before > 0)
            capture.stop()
            capture.start(input)
            repeat(30) { hop(capture, input, sine.next(Hop.frames(RATE)).map { it * gain.get() }.toFloatArray()) }
            assertEquals(0.3f, gain.get())
            assertEquals(before, sink.accepted.size)
        } finally { capture.stop() }
    }

    @Test
    fun `POL-28 capture resume forgets unison before hearing only the drone`() {
        val gain = AtomicReference(1f)
        val sink = FakeHistorySink()
        val capture = LiveCapture(promote = {}, history = sink, sounding = FakeSoundingNotes(69), onDuck = gain::set)
        val input = FakeAudioInput(sampleRate = RATE)
        capture.start(input)
        try {
            val sine = Sine(440.0, 0.2, RATE)
            repeat(30) { hop(capture, input, sine.next(Hop.frames(RATE))) }
            val before = sink.accepted.size
            assertTrue(before > 0)
            val info = capture.state.value.info!!
            capture.onStopped(InputFailure.Disconnected("USB microphone"))
            capture.onResumed(info)
            repeat(30) { hop(capture, input, sine.next(Hop.frames(RATE)).map { it * gain.get() }.toFloatArray()) }
            assertEquals(0.3f, gain.get())
            assertEquals(before, sink.accepted.size)
        } finally { capture.stop() }
    }

    private fun hop(capture: LiveCapture, input: FakeAudioInput, samples: FloatArray) {
        val before = capture.trace.value
        input.push(samples)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (capture.trace.value === before && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("hop reached the capture trace", capture.trace.value !== before)
    }

    private companion object { const val RATE = 48_000 }
}
