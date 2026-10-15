// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.core.contracts.fakes.FakeHistorySink
import ticktock.core.detect.ResponseMode
import ticktock.core.prefs.Prefs
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class TuneSessionTest {
    @Test fun `saved noise floor rejects a quiet note from the first capture`() {
        noiseFloorCase(startDb = -10, changeDb = null)
    }

    @Test fun `changing noise floor takes effect on the analysis thread before the next quiet note`() {
        noiseFloorCase(startDb = -50, changeDb = -10)
    }

    private fun noiseFloorCase(startDb: Int, changeDb: Int?) {
        val input = ticktock.core.contracts.fakes.FakeAudioInput(sampleRate = 48_000)
        val sink = FakeHistorySink()
        val prefs = Prefs(noiseFloorDb = startDb)
        val session = TuneSession(RuntimeEnvironment.getApplication(), prefs, sink,
            makeInput = { input }, micAllowed = { true })
        val sine = ticktock.core.detect.fakes.Sine(440.0, 0.05, 48_000)
        fun play() {
            repeat(16) {
                val before = session.level.value.hop
                input.push(sine.next(ticktock.core.detect.Hop.frames(48_000)))
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (session.level.value.hop <= before) {
                    check(System.nanoTime() < deadline) { "capture did not analyse the hop" }
                    Thread.sleep(2)
                }
            }
        }
        try {
            session.resume()
            play()
            if (changeDb == null) assertEquals(0, synchronized(sink) { sink.accepted.size })
            else {
                org.junit.Assert.assertTrue(synchronized(sink) { sink.accepted.isNotEmpty() })
                session.settingsChanged(prefs.copy(noiseFloorDb = changeDb))
                val before = synchronized(sink) { sink.accepted.size }
                play()
                assertEquals(before, synchronized(sink) { sink.accepted.size })
            }
        } finally { session.close() }
    }

    @Test
    fun `unrelated prefs queue no detector settings and changed settings queue only their field`() {
        val prefs = Prefs()
        val session = TuneSession(RuntimeEnvironment.getApplication(), prefs, FakeHistorySink())
        try {
            val capture = session.javaClass.getDeclaredField("capture").apply { isAccessible = true }.get(session)
            fun pending(name: String): Any? =
                (capture.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(capture) as AtomicReference<*>).getAndSet(null)
            session.settingsChanged(prefs.copy(capturePaused = true, earsFirst = true))
            assertNull(pending("requestedA4"))
            assertNull(pending("requestedMode"))
            val tuned = prefs.copy(a4Hz = 442.0)
            session.settingsChanged(tuned)
            assertEquals(442.0, pending("requestedA4"))
            assertNull(pending("requestedMode"))
            session.settingsChanged(tuned.copy(responseMode = ResponseMode.FAST))
            assertNull(pending("requestedA4"))
            assertEquals(ResponseMode.FAST, pending("requestedMode"))
        } finally {
            session.close()
        }
    }
}
