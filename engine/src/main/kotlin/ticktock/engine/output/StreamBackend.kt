// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent

/**
 * What the control thread asks the platform for when it opens the one output
 * stream (decision 13, SHM-01): shared, low latency, the device's own sample
 * rate and burst, two bursts of buffer. Exclusive MMAP saves perhaps 10 to
 * 20 ms the tuner never feels and has a documented unplug bug on API 28 and
 * 29; an unspecified rate is the common reason a stream is granted low
 * latency at all.
 */
data class StreamRequest(
    val sharing: Sharing = Sharing.SHARED,
    val performance: Performance = Performance.LOW_LATENCY,
    /** [UNSPECIFIED] lets the device choose; the granted rate is the one scheduling uses (OUT-14). */
    val sampleRate: Int = UNSPECIFIED,
    val framesPerCallback: Int = UNSPECIFIED,
    val bufferBursts: Int = INITIAL_BUFFER_BURSTS,
) {
    enum class Sharing { SHARED, EXCLUSIVE }

    enum class Performance { NONE, LOW_LATENCY, POWER_SAVING }

    companion object {
        const val UNSPECIFIED = 0
        const val INITIAL_BUFFER_BURSTS = 2

        /** Growth on xruns stops here (SHM-02); beyond it the latency is the Bluetooth kind and a bigger buffer hides nothing. */
        const val MAX_BUFFER_BURSTS = 4
    }
}

/** The device refused to open; both fields go into the Failed event word for word (OUT-17). */
class StreamOpenFailure(val deviceName: String, val refusedRates: List<Int>) :
    RuntimeException("$deviceName refused ${refusedRates.joinToString()}")

/**
 * What a stream tells the control thread. The bridge may call these from
 * the platform's own threads; the control thread re-posts them to itself and
 * ignores any that arrive from a stream it has already dropped.
 */
interface StreamListener {
    /** The platform ended the stream: unplug, route change before API 37, or an internal error. */
    fun onDisconnected(stream: OpenStream)

    /** API 37: the route moved without a disconnect; the routed device and latency are to be read again (SHM-03). */
    fun onRoutingChanged(stream: OpenStream)

    /** Facts from the render side: beats and counters. */
    fun onEvent(stream: OpenStream, event: OutputEvent)
}

/**
 * One open output stream. Everything here is called on the control thread
 * only. [send] takes the DSP commands (voices, A4, duck, metronome); the
 * registry commands never reach a stream and the mute set arrives as one
 * boolean through [setMuted].
 */
interface OpenStream {
    /** The rate the platform granted; the engine schedules against it and nothing else (OUT-14). */
    val sampleRate: Int

    /** The hardware burst; buffers are sized in multiples of it. */
    val framesPerBurst: Int

    /** The device the stream is playing through right now, or null when the platform cannot say. */
    fun routedDevice(): RoutedDevice?

    /** Output latency as the platform measures it, for the visual beat offset. */
    fun latencyMillis(): Int

    fun send(cmd: OutputCommand)

    fun setMuted(muted: Boolean)

    /** Underruns since open; growth happens when it rises (SHM-02). */
    fun xrunCount(): Int

    /** Asks for [bursts] bursts of buffer; returns what the platform granted. */
    fun setBufferBursts(bursts: Int): Int

    /**
     * Stops and closes. Returns false when the platform did not finish in
     * time; the control thread drops the handle either way so a hung stop
     * never leaves output dead for the rest of the session (OUT-15).
     */
    fun close(): Boolean
}

/**
 * The seam between the control thread and the JNI bridge. The control thread
 * is the only caller of [open], and it holds at most one [OpenStream] at a
 * time (OUT-01). The bridge implements this over AAudio; the harness
 * implements it with a fake that can refuse, disconnect, change rate and
 * report xruns.
 */
interface StreamBackend {
    /** Opens the stream or fails with a [StreamOpenFailure]. */
    fun open(request: StreamRequest, listener: StreamListener): Result<OpenStream>
}
