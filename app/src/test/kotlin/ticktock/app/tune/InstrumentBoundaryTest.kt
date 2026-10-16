// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.core.contracts.SoundingNotes
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.contracts.fakes.FakeHistorySink
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.core.prefs.Prefs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class InstrumentBoundaryTest {
    @Test fun `RNG-14 rapid instrument round trip cannot admit an in-flight or queued old batch`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val intercept = AtomicBoolean(false)
        val sounding = object : SoundingNotes {
            override fun current(): IntArray {
                if (intercept.getAndSet(false)) {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS)) { "analysis interleaving was not released" }
                }
                return intArrayOf()
            }
        }
        val input = FakeAudioInput(48_000)
        val sink = FakeHistorySink()
        val prefs = Prefs(instrumentKey = "eb_alto")
        val session = TuneSession(RuntimeEnvironment.getApplication(), prefs, sink,
            makeInput = { input }, micAllowed = { true }, sounding = sounding)
        val tone = Sine(440.0, 0.1, 48_000)
        val frames = Hop.frames(48_000)
        fun awaitHop(hop: Long) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (session.level.value.hop < hop) {
                check(System.nanoTime() < deadline) { "analysis did not reach hop $hop" }
                Thread.sleep(2)
            }
        }
        fun play() {
            val next = session.level.value.hop + 1
            assertTrue(input.push(tone.next(frames)))
            awaitHop(next)
        }
        try {
            session.resume()
            repeat(24) { play() }
            assertEquals(69, session.state.value.readout.midi)
            val saved = synchronized(sink) { sink.accepted.toList() }
            val before = session.level.value.hop
            intercept.set(true)
            assertTrue(input.push(tone.next(frames)))
            assertTrue("old decision is in flight", entered.await(5, TimeUnit.SECONDS))
            assertTrue(input.push(tone.next(8 * frames)))
            session.settingsChanged(prefs.copy(instrumentKey = "flute_c"))
            session.settingsChanged(prefs) // identical key, but a different instrument epoch
            resume.countDown()
            awaitHop(before + 9)
            assertEquals("no old reading is admitted after either switch", saved,
                synchronized(sink) { sink.accepted.toList() })
            assertNull(session.state.value.readout.midi)
            assertNull(session.lastNote.value)
            assertEquals(0, session.trace.value.attacks)
            assertTrue(session.trace.value.cents.all { it.isNaN() })
            assertTrue(input.isOpen)
            repeat(24) { play() }
            assertEquals(69, session.state.value.readout.midi)
            assertTrue(synchronized(sink) { sink.accepted.size > saved.size })
        } finally { resume.countDown(); session.close() }
    }
}
