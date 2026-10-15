// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ticktock.core.contracts.AudioInput
import ticktock.core.contracts.AudioSink
import ticktock.core.contracts.InputInfo
import ticktock.core.detect.AnalysisCounters
import ticktock.core.detect.Decision
import ticktock.core.detect.Hop
import ticktock.core.detect.HopAnalyser
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Runs the detector off the UI thread (DET-16). The capture thread writes
 * into the analyser's ring and wakes this thread; this thread drains every
 * whole hop, hands the decisions to [onDecisions] in order, and publishes the
 * newest one on [latest] for a screen that only ever wants the current state.
 *
 * Dropped hops and analysis errors are the analyser's own counters, read
 * through [counters] whenever Diagnostics asks (POL-18). [lastHopNanos] is
 * the cost of the most recent drain per hop, the figure DET-17 measures on
 * the reference device.
 */
class AnalysisThread(
    val analyser: HopAnalyser,
    /** Raises the thread to the platform's audio priority on a device; a no-op on the JVM. */
    private val promote: () -> Unit = {},
    /** Applies queued settings on the analysis thread before any hop in the batch is decided. */
    private val beforeBatch: () -> Unit = {},
    /** Every decision, in hop order, on the analysis thread. */
    private val onDecisions: (List<Decision>) -> Unit = {},
) {
    private val latestFlow = MutableStateFlow<Decision?>(null)

    /** The newest decision, or null while stopped. Conflated: a slow collector sees the latest only. */
    val latest: StateFlow<Decision?> = latestFlow

    val counters: AnalysisCounters get() = analyser.counters

    @Volatile
    var lastHopNanos: Long = 0
        private set

    private val wake = Semaphore(0)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    /** Opens [input] through the analyser and starts analysing; a second start replaces the first. */
    fun start(input: AudioInput): Result<InputInfo> {
        stop()
        val result = analyser.attach(Waking(input))
        result.onSuccess {
            running = true
            thread = Thread(::loop, THREAD_NAME).also { it.start() }
        }
        return result
    }

    fun stop() {
        val worker = thread
        running = false
        wake.release()
        worker?.join()
        thread = null
        analyser.detach()
        latestFlow.value = null
    }

    private fun loop() {
        promote()
        while (running) {
            // Woken by every delivery; the timeout keeps the loop honest if a wake is ever lost.
            wake.tryAcquire(WAKE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            wake.drainPermits()
            if (!running) break
            val started = System.nanoTime()
            beforeBatch()
            val decisions = analyser.drain()
            if (decisions.isEmpty()) continue
            lastHopNanos = (System.nanoTime() - started) / decisions.size
            onDecisions(decisions)
            latestFlow.value = decisions.last()
        }
    }

    /** The input as the analyser sees it: every block delivered also wakes the loop. */
    private inner class Waking(private val real: AudioInput) : AudioInput {
        override fun start(sink: AudioSink): Result<InputInfo> = real.start { samples, frames ->
            sink(samples, frames)
            wake.release()
        }

        override fun stop() = real.stop()
    }

    companion object {
        const val THREAD_NAME = "analysis"

        /** One hop: long enough that an idle thread costs nothing, short enough that a lost wake is invisible. */
        val WAKE_TIMEOUT_MILLIS = Hop.MILLIS.toLong()
    }
}
