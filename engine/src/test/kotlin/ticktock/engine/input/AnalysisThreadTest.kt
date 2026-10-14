// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.input

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Decision
import ticktock.core.detect.Detector
import ticktock.core.detect.Hop
import ticktock.core.detect.HopAnalyser
import ticktock.core.detect.HopEstimate
import ticktock.core.detect.PitchEstimator
import ticktock.core.detect.ResponseMode
import ticktock.core.detect.YinEstimator
import ticktock.core.detect.fakes.Sine
import ticktock.core.music.Pitch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * DET-16 and POL-18 on the real detector: audio pushed from the test thread
 * is analysed on the analysis thread, and the counters it keeps are the ones
 * the screen reads.
 */
class AnalysisThreadTest {
    private val rate = 48_000
    private val hopFrames = Hop.frames(rate)
    private val decisions = CopyOnWriteArrayList<Decision>()
    private val threads = CopyOnWriteArrayList<String>()
    private var worker: AnalysisThread? = null

    private fun worker(estimator: PitchEstimator? = null, promote: () -> Unit = {}): AnalysisThread {
        val detector = Detector(ResponseMode.NORMAL)
        val analyser = HopAnalyser(detector, estimator ?: YinEstimator(detector))
        return AnalysisThread(analyser, promote) { batch ->
            threads += Thread.currentThread().name
            decisions += batch
        }.also { worker = it }
    }

    @After
    fun stopWorker() {
        worker?.stop()
    }

    @Test
    fun `DET-16 the detector runs on the analysis thread and reads the note pushed from another thread`() {
        val input = FakeAudioInput(sampleRate = rate)
        val worker = worker(promote = { threads += "promoted:" + Thread.currentThread().name })
        worker.start(input).getOrThrow()
        val sine = Sine(Pitch.hzFromMidi(69, 440.0), 0.1, rate)

        repeat(10) { input.push(sine.next(hopFrames)) }

        awaitUntil("ten decisions") { decisions.size == 10 }
        assertEquals((0L until 10).toList(), decisions.map { it.hop })
        assertEquals(setOf("promoted:${AnalysisThread.THREAD_NAME}", AnalysisThread.THREAD_NAME), threads.toSet())
        assertFalse(Thread.currentThread().name == AnalysisThread.THREAD_NAME)
        val latest = worker.latest.value ?: error("no latest decision")
        assertEquals(69, latest.incumbent)
        assertEquals(440.0, latest.display?.hz ?: 0.0, 0.5)
        assertTrue("per-hop cost recorded", worker.lastHopNanos > 0)
        assertEquals(10, worker.counters.hopsAnalysed)
    }

    @Test
    fun `POL-18 dropped hops and analysis errors are reported through the thread's counters`() {
        val throwing = PitchEstimator { _, _, _ -> throw IllegalStateException("estimator broke") }
        val input = FakeAudioInput(sampleRate = rate)
        val worker = worker(estimator = throwing)
        worker.start(input).getOrThrow()

        // Far more than the ring holds in one block: the oldest is pushed out and counted.
        input.push(FloatArray(hopFrames * (HopAnalyser.DEFAULT_RING_HOPS + 4)))

        awaitUntil("ring drained") { worker.counters.analysisErrors > 0 && worker.analyser.counters.hopsAnalysed == 0L }
        awaitUntil("errors counted") { worker.counters.analysisErrors >= HopAnalyser.DEFAULT_RING_HOPS - 1 }
        val counters = worker.counters
        assertTrue("dropped ${counters.droppedHops}", counters.droppedHops >= 4)
        assertEquals("estimator broke", counters.lastAnalysisError?.substringAfter(": "))
        assertNull("a thrown hop is no decision", worker.latest.value)
    }

    @Test
    fun `stop joins the thread, closes the input and clears the latest decision`() {
        val input = FakeAudioInput(sampleRate = rate)
        val worker = worker(estimator = PitchEstimator { _, _, _ -> HopEstimate(440.0, 0.01, 0.1) })
        worker.start(input).getOrThrow()
        input.push(FloatArray(hopFrames * 3))
        awaitUntil("decisions") { worker.latest.value != null }

        worker.stop()

        assertFalse(worker.isRunning)
        assertEquals(0, input.openCount)
        assertNull(worker.latest.value)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == AnalysisThread.THREAD_NAME && it.isAlive })

        // A second start is a fresh stream.
        worker.start(input).getOrThrow()
        input.push(FloatArray(hopFrames * 3))
        awaitUntil("decisions again") { worker.latest.value != null }
        assertEquals(1, input.openCount)
    }

    private fun awaitUntil(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(5)
        }
    }
}
