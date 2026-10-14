// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output.fakes

import android.media.AudioDeviceInfo
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.engine.output.OpenStream
import ticktock.engine.output.RoutedDevice
import ticktock.engine.output.StreamBackend
import ticktock.engine.output.StreamListener
import ticktock.engine.output.StreamOpenFailure
import ticktock.engine.output.StreamRequest
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * A stream the test plays: it records what it was sent and can end,
 * re-route, underrun and refuse to stop in time. [continuesEngine] says
 * whether it opened on the engine a parked stream kept.
 */
class FakeOpenStream(
    override val sampleRate: Int,
    override val framesPerBurst: Int,
    var routed: RoutedDevice?,
    var latency: Int,
    private val closeTimesOut: Boolean,
    val continuesEngine: Boolean,
) : OpenStream {
    lateinit var listener: StreamListener
    val sent = mutableListOf<OutputCommand>()

    /** Null until the control thread has said anything about muting. */
    var muted: Boolean? = null
    var xruns = 0
    var bursts = StreamRequest.INITIAL_BUFFER_BURSTS
    var closed = false
        private set

    /** True after a park, false after a close, null while open. */
    var engineKept: Boolean? = null
        private set

    override fun routedDevice(): RoutedDevice? = routed
    override fun latencyMillis(): Int = latency
    override fun send(cmd: OutputCommand) {
        sent += cmd
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    /** When set, what the next buffer request is granted, whatever it asked; the native path grows on its own. */
    var nextGrant: Int? = null

    override fun xrunCount(): Int = xruns
    override fun setBufferBursts(bursts: Int): Int {
        this.bursts = nextGrant ?: bursts
        return this.bursts
    }

    override fun close(): Boolean = end(keep = false)

    override fun park(): Boolean = end(keep = true)

    private fun end(keep: Boolean): Boolean {
        closed = true
        engineKept = keep && !closeTimesOut
        return !closeTimesOut
    }

    fun disconnect() = listener.onDisconnected(this)

    fun routeChanged(to: RoutedDevice) {
        routed = to
        listener.onRoutingChanged(this)
    }

    fun event(event: OutputEvent) = listener.onEvent(this, event)
}

/** Hands out [FakeOpenStream]s with whatever the next open should grant, or refuses. */
class FakeStreamBackend : StreamBackend {
    val requests = mutableListOf<StreamRequest>()
    val opened = mutableListOf<FakeOpenStream>()

    var nextSampleRate = 48_000
    var nextFramesPerBurst = 96
    var nextRouted: RoutedDevice? = SPEAKER
    var nextLatency = 20
    var nextCloseTimesOut = false
    var refusal: StreamOpenFailure? = null

    /** The stream that is open now, if any. */
    val current: FakeOpenStream? get() = opened.lastOrNull()?.takeIf { !it.closed }

    /** The stream whose engine waits for the next open, if any. */
    val parked: FakeOpenStream? get() = opened.lastOrNull()?.takeIf { it.engineKept == true && !discardedAfter.contains(it) }

    /** Engines dropped by [discard]; a discard with nothing kept is not counted. */
    val discards: Int get() = discardedAfter.size
    private val discardedAfter = mutableSetOf<FakeOpenStream>()

    /** Name of the thread each open ran on: the control thread and nothing else (OUT-15). */
    val openThreads = Collections.synchronizedList(mutableListOf<String>())

    /** Counts down when an open begins, so a test can see one in progress. */
    val openStarted = CountDownLatch(1)

    /** When set, an open blocks here until the test releases it, the shape of a slow driver. */
    var openGate: CountDownLatch? = null

    override fun open(request: StreamRequest, listener: StreamListener): Result<OpenStream> {
        openThreads += Thread.currentThread().name
        openStarted.countDown()
        openGate?.await()
        requests += request
        refusal?.let { return Result.failure(it) }
        val continues = parked != null
        val stream = FakeOpenStream(nextSampleRate, nextFramesPerBurst, nextRouted, nextLatency, nextCloseTimesOut, continues)
        stream.listener = listener
        opened += stream
        return Result.success(stream)
    }

    override fun discard() {
        parked?.let { discardedAfter += it }
    }

    companion object {
        val SPEAKER = RoutedDevice(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, name = "Built-in speaker")
        val WIRED_HEADSET = RoutedDevice(id = 2, type = AudioDeviceInfo.TYPE_WIRED_HEADSET, name = "Wired headset")
        val BLE_HEADSET = RoutedDevice(id = 3, type = AudioDeviceInfo.TYPE_BLE_HEADSET, name = "LE earbuds")
        val USB_DAC = RoutedDevice(id = 4, type = AudioDeviceInfo.TYPE_USB_DEVICE, name = "USB DAC")
    }
}
