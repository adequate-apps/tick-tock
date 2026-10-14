// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.InputInfo
import ticktock.core.contracts.InputSource
import ticktock.core.detect.Hop
import ticktock.core.detect.HopEstimate
import ticktock.core.detect.fakes.ZeroCrossingEstimator
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** The emulator's known tone: paced in real time, phase-continuous, and read back as the pitch it was given. */
class ToneInputTest {
    @Test
    fun `the tone arrives in real time as one continuous sine at the frequency asked for`() {
        val rate = 48_000
        val input = ToneInput(hz = 440.0, amplitude = 0.1, sampleRate = rate)
        val received = ArrayList<Float>()
        val started = System.nanoTime()

        val info = input.start { samples, frames -> synchronized(received) { for (i in 0 until frames) received += samples[i] } }.getOrThrow()
        Thread.sleep(250)
        input.stop()
        val elapsedSeconds = (System.nanoTime() - started) / 1e9

        assertEquals(InputInfo(rate, ToneInput.DEVICE_NAME, InputSource.FAKE), info)
        val frames = synchronized(received) { received.size }
        // Paced by the wall clock: about a quarter second of audio, allowing one block either side.
        val block = Hop.frames(rate) / 4
        assertTrue("$frames frames in $elapsedSeconds s", frames >= 0.25 * rate - block && frames <= elapsedSeconds * rate + block)

        // One continuous signal: no step larger than the sine's own slope between any two samples.
        val maxStep = 0.1 * 2.0 * Math.PI * 440.0 / rate * 1.05
        for (i in 1 until frames) assertTrue("step at $i", abs(received[i] - received[i - 1]) <= maxStep)

        val window = FloatArray(Hop.windowFrames(rate)) { received[it] }
        val estimate: HopEstimate = ZeroCrossingEstimator().estimate(window, window.size, rate)
        assertEquals(440.0, estimate.hz, 0.5)
    }

    @Test
    fun `stop ends delivery and a second start is a fresh stream`() {
        val input = ToneInput(hz = 220.0, amplitude = 0.05)
        var deliveries = 0
        input.start { _, _ -> deliveries++ }.getOrThrow()
        Thread.sleep(60)
        input.stop()
        val afterStop = deliveries
        Thread.sleep(60)
        assertEquals(afterStop, deliveries)

        var again = 0
        input.start { _, _ -> again++ }.getOrThrow()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000)
        while (again == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        input.stop()
        assertTrue(again > 0)
        assertEquals(afterStop, deliveries)
    }
}
