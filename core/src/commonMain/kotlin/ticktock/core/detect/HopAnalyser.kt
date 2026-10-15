// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized
import ticktock.core.contracts.AudioInput
import ticktock.core.contracts.AudioSink
import ticktock.core.contracts.InputInfo

/** Diagnostics since the stream started (POL-18). */
data class AnalysisCounters(
    val hopsAnalysed: Long = 0,
    /**
     * Hops that produced no decision: pushed out of the ring unread,
     * delivered before the stream was configured, or spent as context after
     * a gap, because the window they would have completed never existed.
     */
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
 * window two hops long (DET-14). After a gap the hop before is no neighbour
 * of the hop after, so the first hop past a gap is context alone and the
 * numbering moves on by the hops lost: a hop index stays a time (POL-09).
 * The window is handed to the estimator unscaled; mic gain is applied by
 * the detector to the gate alone (POL-12).
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

    /** True when the hop in [previous] ends before audio the ring dropped, so it can only be context. */
    private var contextOnly = false
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
            addDropped(frames.toLong())
            return
        }
        target.write(samples, frames)
    }

    // The capture thread and the analysis thread both add to the drop count.
    @Synchronized
    private fun addDropped(frames: Long) {
        droppedFrames += frames
    }

    @Synchronized
    private fun resetDropped() {
        droppedFrames = 0
    }

    private fun resetCounters() {
        resetDropped()
        hopsAnalysed = 0
        analysisErrors = 0
        lastAnalysisError = null
        nextHop = 0
        contextOnly = false
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
        while (true) {
            val lost = target.readHop(current, hopFrames) ?: break
            if (lost > 0) skipGap(lost)
            val hop = nextHop++
            if (contextOnly) {
                // The first hop after a gap: the window it would complete, half
                // from before the gap, never existed. It is kept as the context
                // for the next hop and counted as a hop without a decision.
                contextOnly = false
                addDropped(hopFrames.toLong())
                swapHops()
                continue
            }
            previous.copyInto(window, 0, 0, hopFrames)
            current.copyInto(window, hopFrames, 0, hopFrames)
            val rms = HopEstimate.rms(window, window.size)
            val estimate = if (detector.belowFloor(rms, micGain)) {
                // The gate rejects the window as silence before it reads any
                // period, so the estimator's work could change nothing; its
                // level still reaches the meter. Most hops of a practice
                // session are silent, and this is where that costs nothing
                // (DET-17). A non-finite window is never below the floor and
                // goes to the estimator, which counts it (DET-15).
                HopEstimate.none(rms)
            } else try {
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
        // Audio pushed out while no whole hop was readable counts now, and the
        // next hop read is already past the gap.
        val lost = target.takeOverflow()
        if (lost > 0) skipGap(lost)
        return decisions
    }

    /** The ring dropped [lostFrames] unread: count them, move the numbering on by the whole hops among them, and mark the context stale. */
    private fun skipGap(lostFrames: Long) {
        addDropped(lostFrames)
        nextHop += lostFrames / hopFrames
        contextOnly = true
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
