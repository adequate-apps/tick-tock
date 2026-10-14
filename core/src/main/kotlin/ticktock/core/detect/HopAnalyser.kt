// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import ticktock.core.contracts.AudioInput
import ticktock.core.contracts.AudioSink
import ticktock.core.contracts.InputInfo

/** Diagnostics since the stream started (POL-18). */
data class AnalysisCounters(
    val hopsAnalysed: Long = 0,
    /** Hops of audio that never reached the estimator: ring overflow, or delivery before the stream was configured. */
    val droppedHops: Long = 0,
    /** Hops the estimator threw on; each was skipped and the stream continued (ERR-07). */
    val analysisErrors: Long = 0,
    val lastAnalysisError: String? = null,
)

/**
 * Turns delivered audio into hops and hops into decisions. The capture side
 * calls the sink this class hands to [AudioInput.start]; the analysis side
 * calls [drain]. Between them sits a [HopRing], so a late analysis side
 * catches up on everything still in the ring and the overflow is counted,
 * and no hop is ever analysed twice (POL-15).
 *
 * Each hop is analysed with the hop before it as context, which makes the
 * window two hops long (DET-14). The window is handed to the estimator
 * unscaled; mic gain is applied by the detector to the gate alone (POL-12).
 */
class HopAnalyser(
    val detector: Detector,
    private val estimator: PitchEstimator,
    /** Audio the ring holds before the oldest hop is pushed out. */
    private val ringHops: Int = DEFAULT_RING_HOPS,
) {
    init {
        require(ringHops >= Hop.WINDOW_HOPS) { "the ring must hold at least one window" }
    }

    var micGain: Double = 1.0

    var sampleRate: Int = 0
        private set
    var hopFrames: Int = 0
        private set

    @Volatile
    private var ring: HopRing? = null

    @Volatile
    private var session = 0

    private var previous = FloatArray(0)
    private var current = FloatArray(0)
    private var window = FloatArray(0)
    private var nextHop = 0L
    private var droppedFrames = 0L
    private var hopsAnalysed = 0L
    private var analysisErrors = 0L
    private var lastAnalysisError: String? = null
    private var input: AudioInput? = null

    val counters: AnalysisCounters
        get() = AnalysisCounters(
            hopsAnalysed = hopsAnalysed,
            droppedHops = if (hopFrames > 0) droppedFrames / hopFrames else 0,
            analysisErrors = analysisErrors,
            lastAnalysisError = lastAnalysisError,
        )

    /**
     * Opens [input] and starts a fresh stream: hop numbering, counters and the
     * detector's held note start over. The input replaces any stream it had
     * open (POL-17); a sink from an earlier call drops what it is given, so a
     * late block from an old stream can never land in the new one.
     *
     * The input must return from [AudioInput.start] before it delivers:
     * the ring is sized from the rate it reports.
     */
    fun attach(input: AudioInput): Result<InputInfo> {
        val mine = ++session
        ring = null
        // Exactly one stream is open: a different input replaces the old one here,
        // the same input replaces its own stream inside start().
        this.input?.takeIf { it !== input }?.stop()
        resetCounters()
        val sink: AudioSink = { samples, frames -> deliver(mine, samples, frames) }
        val result = input.start(sink)
        result.onSuccess { info -> configure(info) }
        this.input = input.takeIf { result.isSuccess }
        return result
    }

    /** Stops the input; audio delivered afterwards is dropped. */
    fun detach() {
        session++
        ring = null
        input?.stop()
        input = null
    }

    private fun deliver(forSession: Int, samples: FloatArray, frames: Int) {
        if (forSession != session) return
        val target = ring
        if (target == null) {
            // Delivered before configure(): counted, never silently lost.
            synchronized(this) { droppedFrames += frames }
            return
        }
        target.write(samples, frames)
    }

    private fun resetCounters() {
        synchronized(this) { droppedFrames = 0 }
        hopsAnalysed = 0
        analysisErrors = 0
        lastAnalysisError = null
        nextHop = 0
    }

    private fun configure(info: InputInfo) {
        sampleRate = info.sampleRate
        hopFrames = Hop.frames(sampleRate)
        previous = FloatArray(hopFrames)
        current = FloatArray(hopFrames)
        window = FloatArray(hopFrames * Hop.WINDOW_HOPS)
        // The preset is whatever the chain managed to open; the gate is scaled for it (SRC-03).
        detector.setSource(info.source)
        detector.reset()
        ring = HopRing(hopFrames * ringHops)
    }

    /**
     * Analyses every whole hop in the ring, in order, and returns their
     * decisions. Call from the analysis thread, or directly in a test.
     */
    fun drain(): List<Decision> {
        val target = ring ?: return emptyList()
        val decisions = ArrayList<Decision>()
        while (target.read(current, hopFrames)) {
            System.arraycopy(previous, 0, window, 0, hopFrames)
            System.arraycopy(current, 0, window, hopFrames, hopFrames)
            val hop = nextHop++
            val estimate = try {
                estimator.estimate(window, window.size, sampleRate)
            } catch (failure: RuntimeException) {
                // One bad hop is skipped and counted; the stream goes on (ERR-07).
                analysisErrors++
                lastAnalysisError = failure.toString()
                swapHops()
                continue
            }
            hopsAnalysed++
            decisions += detector.step(estimate, hop, micGain)
            swapHops()
        }
        synchronized(this) { droppedFrames += target.takeOverflow() }
        return decisions
    }

    private fun swapHops() {
        val finished = current
        current = previous
        previous = finished
    }

    companion object {
        /** About three quarters of a second; a pause longer than this drops audio rather than growing memory. */
        const val DEFAULT_RING_HOPS = 16
    }
}
