// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output.fakes

import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason
import ticktock.engine.NativeBridge
import ticktock.engine.NativeCalls
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The native controller as the Kotlin side sees it through the JNI bridge,
 * without the library: handles, the registry that opens on the first tool
 * and closes on the last, the event ring and the drain that waits for it.
 * A test plays the device from here: beats, counters, a disconnect, a
 * refused open, a drain that never returns.
 */
class FakeNativeCalls : NativeCalls {
    /** One native call as the test sees it: the method and its arguments after the handle. */
    data class Call(val name: String, val args: List<Any?> = emptyList())

    inner class Handle(val id: Long) {
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        val events = LinkedBlockingQueue<Event>()
        var registered = 0
        var streamOpen = false
        var parked = false
        var destroyed = false
        var underruns = 0L
        var bufferBursts = 2L
        var lateBeatsDropped = 0L
        var framesRendered = 0L

        fun calls(name: String): List<Call> = synchronized(calls) { calls.filter { it.name == name } }

        /** The render side reports a beat; the drain delivers it. */
        fun beat(index: Long, beatInBar: Int, frame: Long, nanos: Long) {
            events += Event(KIND_BEAT, index, beatInBar.toLong(), frame, nanos)
        }

        fun counters(late: Long, underruns: Long) {
            lateBeatsDropped = late
            this.underruns = underruns
            events += Event(KIND_COUNTERS, late, underruns)
        }

        /** The device's error callback fired; the controller closes and parks on its next drain. */
        fun disconnect() {
            streamOpen = false
            parked = true
            events += Event(KIND_PARKED, ParkReason.HEADPHONES_DISCONNECTED.ordinal.toLong(), tools = registered)
        }
    }

    data class Event(
        val kind: Int,
        val a: Long = 0,
        val b: Long = 0,
        val c: Long = 0,
        val d: Long = 0,
        val tools: Int = 0,
        val ints: IntArray? = null,
    )

    val handles = Collections.synchronizedMap(linkedMapOf<Long, Handle>())
    private var nextHandle = 1L

    var sampleRate = 48_000L
    var framesPerBurst = 96L
    var deviceId = 7L
    var latencyMillis = 23L

    /** When set, every open is refused and Failed names this device with the two fallback rates. */
    var refuseOpens = false

    /** When set, a drain blocks here until [releaseDrains] runs, the shape of a library that never returns. */
    var hangDrains = false
    private val drainGate = CountDownLatch(1)

    /** The thread each waiting drain ran on; the no-wait verdict drain at open is the caller's. */
    val drainThreads = Collections.synchronizedSet(mutableSetOf<String>())

    fun handle(id: Long): Handle = checkNotNull(handles[id]) { "no handle $id" }
    val single: Handle get() = synchronized(handles) { handles.values.single() }

    fun releaseDrains() = drainGate.countDown()

    override fun nativeAbiVersion(): Int = ticktock.engine.EngineLibrary.ABI_VERSION

    override fun nativeCreate(): Long = synchronized(handles) {
        val id = nextHandle++
        handles[id] = Handle(id)
        id
    }

    override fun nativeDestroy(handle: Long) {
        handle(handle).destroyed = true
        lifetimeTrace += "destroy"
    }

    override fun nativeRegister(handle: Long, tool: Int) {
        val h = handle(handle)
        synchronized(h) {
            h.calls += Call("register", listOf(tool))
            val wasEmpty = h.registered == 0
            h.registered = h.registered or (1 shl tool)
            if (wasEmpty && !h.streamOpen && !h.parked) {
                if (refuseOpens) {
                    h.parked = true
                    h.events += Event(KIND_FAILED, deviceId, ints = intArrayOf(48_000, 44_100))
                } else {
                    h.streamOpen = true
                    h.events += Event(KIND_OPENED, sampleRate, framesPerBurst, deviceId, latencyMillis)
                }
            }
        }
    }

    override fun nativeUnregister(handle: Long, tool: Int) {
        val h = handle(handle)
        synchronized(h) {
            h.calls += Call("unregister", listOf(tool))
            h.registered = h.registered and (1 shl tool).inv()
            if (h.registered != 0) return
            h.streamOpen = false
            h.parked = false
            h.events += Event(KIND_CLOSED)
        }
    }

    override fun nativeVoiceOn(handle: Long, slot: Int, midi: Int, voice: Int, gain: Float): Boolean =
        record(handle, "voiceOn", slot, midi, voice, gain)

    override fun nativeVoiceOff(handle: Long, slot: Int): Boolean = record(handle, "voiceOff", slot)

    override fun nativeVoiceGain(handle: Long, slot: Int, gain: Float): Boolean = record(handle, "voiceGain", slot, gain)

    override fun nativeSetA4(handle: Long, hz: Double): Boolean = record(handle, "setA4", hz)

    override fun nativeDuck(handle: Long, gain: Float): Boolean = record(handle, "duck", gain)

    override fun nativeMetroStart(
        handle: Long, bpm: Int, beatsPerBar: Int, beatUnit: Int, compoundPulse: Boolean, accents: IntArray,
        subdivision: Int, click: Int, gain: Float, barsOn: Int, barsOff: Int,
    ): Boolean = record(
        handle, "metroStart", bpm, beatsPerBar, beatUnit, compoundPulse, accents.toList(), subdivision, click, gain, barsOn, barsOff,
    )

    override fun nativeMetroUpdate(
        handle: Long, bpm: Int, beatsPerBar: Int, beatUnit: Int, compoundPulse: Boolean, accents: IntArray,
        subdivision: Int, click: Int, gain: Float, barsOn: Int, barsOff: Int,
    ): Boolean = record(
        handle, "metroUpdate", bpm, beatsPerBar, beatUnit, compoundPulse, accents.toList(), subdivision, click, gain, barsOn, barsOff,
    )

    override fun nativeMetroStop(handle: Long): Boolean = record(handle, "metroStop")

    override fun nativeMute(handle: Long, reason: Int, active: Boolean): Boolean = record(handle, "mute", reason, active)

    /** As the native controller: a park with tools registered closes the stream and keeps them; parked twice is one park. */
    override fun nativePark(handle: Long, reason: Int) {
        val h = handle(handle)
        synchronized(h) {
            h.calls += Call("park", listOf(reason))
            if (h.registered == 0 || h.parked) return
            h.streamOpen = false
            h.parked = true
            h.events += Event(KIND_PARKED, reason.toLong(), tools = h.registered)
        }
    }

    /** As the native controller: reopens for the parked set at whatever the device now gives, or reports the refusal. */
    override fun nativeResume(handle: Long) {
        val h = handle(handle)
        synchronized(h) {
            h.calls += Call("resume")
            if (!h.parked || h.registered == 0 || h.streamOpen) return
            if (refuseOpens) {
                h.events += Event(KIND_FAILED, deviceId, ints = intArrayOf(48_000, 44_100))
                return
            }
            h.parked = false
            h.streamOpen = true
            h.events += Event(KIND_RESUMED, sampleRate, framesPerBurst, deviceId, latencyMillis, tools = h.registered)
        }
    }

    /** Set to make the next [nativeDiagnostics] wait until [releaseDiagnostics], the shape of a reader inside the library. */
    var holdDiagnostics = false
    private val diagnosticsGate = CountDownLatch(1)

    /** Every call that touches a handle's lifetime, in order: "diagnostics" when a read returns, "destroy" when a handle goes. */
    val lifetimeTrace = Collections.synchronizedList(mutableListOf<String>())

    fun releaseDiagnostics() = diagnosticsGate.countDown()

    override fun nativeDrainEvents(handle: Long, listener: NativeBridge.Listener, timeoutMillis: Int): Int {
        if (timeoutMillis > 0) {
            drainThreads += Thread.currentThread().name
            // The verdict drain at open passes no wait; only the event thread hangs here.
            if (hangDrains) drainGate.await()
        }
        val h = handle(handle)
        val first = h.events.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS) ?: return 0
        val pending = mutableListOf(first)
        h.events.drainTo(pending)
        pending.forEach { listener.onEvent(it.kind, it.a, it.b, it.c, it.d, it.tools, it.ints) }
        return pending.size
    }

    override fun nativeLatencyMillis(handle: Long): Int = latencyMillis.toInt()

    override fun nativeDiagnostics(handle: Long): LongArray {
        val h = handle(handle)
        check(!h.destroyed) { "nativeDiagnostics on a destroyed handle $handle" }
        if (holdDiagnostics) diagnosticsGate.await()
        lifetimeTrace += "diagnostics"
        return longArrayOf(
            h.lateBeatsDropped, h.underruns, h.bufferBursts, 0, 0, 0, h.framesRendered, sampleRate,
            if (h.streamOpen) 1 else 0, if (h.parked) 1 else 0,
        )
    }

    private fun record(handle: Long, name: String, vararg args: Any?): Boolean {
        handle(handle).calls += Call(name, args.toList())
        return true
    }

    companion object {
        // OutputEvent's declaration order, as the bridge numbers the kinds.
        const val KIND_OPENED = 0
        const val KIND_CLOSED = 1
        const val KIND_BEAT = 2
        const val KIND_PARKED = 3
        const val KIND_RESUMED = 4
        const val KIND_FAILED = 5
        const val KIND_COUNTERS = 6

        fun mask(vararg tools: OutputTool): Int = tools.fold(0) { acc, t -> acc or (1 shl t.ordinal) }
    }
}
