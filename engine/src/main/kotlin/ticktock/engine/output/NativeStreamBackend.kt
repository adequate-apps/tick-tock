// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputTool
import ticktock.core.metro.MetroSetup
import ticktock.engine.NativeBridge
import ticktock.engine.NativeCalls

/**
 * The [StreamBackend] over the JNI bridge: the one the control thread uses
 * on a device.
 *
 * Every open is its own native handle. The native controller carries a
 * registry and a parked flag of its own; giving each stream a fresh handle
 * means a native park can never outlive the stream it belongs to, and the
 * Kotlin registry in the control thread stays the only registry that
 * decides anything. One fixed [HOLDER] registration keeps the native
 * registry non-empty for the life of the stream, which is all the native
 * side needs from it.
 *
 * What the native side decides on its own, by decision 13: SHARED,
 * LOW_LATENCY, the device's rate and burst, two bursts of buffer grown one
 * per underrun to four inside the render callback. A [StreamRequest] that
 * asks for anything else is refused here before the library is touched,
 * because the bridge has no way to pass it through.
 *
 * [outputs] is how a device id becomes a [RoutedDevice]: the platform's
 * current output list, read on the control thread.
 */
class NativeStreamBackend(
    private val native: NativeCalls,
    private val outputs: () -> List<RoutedDevice>,
) : StreamBackend {
    /** The stream open now; read by Diagnostics from any thread, written by the control thread. */
    @Volatile
    private var current: NativeOpenStream? = null

    /** The native counters of the open stream, or null while none is open. */
    fun diagnostics(): NativeDiagnostics? = current?.diagnostics()

    override fun open(request: StreamRequest, listener: StreamListener): Result<OpenStream> {
        if (request != HONOURED) {
            return Result.failure(IllegalArgumentException("the bridge opens $HONOURED only; asked for $request"))
        }
        val handle = native.nativeCreate()
        native.nativeRegister(handle, HOLDER.ordinal)
        // The native registry opened the stream inside that call, so its
        // verdict is already queued: one drain with no wait reads it.
        val verdict = object : NativeBridge.Listener {
            var first: NativeEvent? = null
            override fun onEvent(kind: Int, a: Long, b: Long, c: Long, d: Long, tools: Int, ints: IntArray?) {
                if (first == null) first = NativeEvent.decode(kind, a, b, c, d, tools, ints)
            }
        }
        native.nativeDrainEvents(handle, verdict, 0)
        return when (val outcome = verdict.first) {
            is NativeEvent.Opened -> {
                val stream = NativeOpenStream(native, handle, outcome, listener, outputs) { current = null }
                current = stream
                stream.startDraining()
                Result.success(stream)
            }
            is NativeEvent.Failed -> {
                native.nativeDestroy(handle)
                Result.failure(StreamOpenFailure(deviceName(outcome.deviceId), outcome.refusedRates))
            }
            else -> {
                native.nativeDestroy(handle)
                Result.failure(StreamOpenFailure(UNKNOWN_DEVICE, emptyList()))
            }
        }
    }

    private fun deviceName(deviceId: Int): String = outputs().firstOrNull { it.id == deviceId }?.name ?: "output device $deviceId"

    companion object {
        /** The request the native side honours; see the class comment. */
        val HONOURED = StreamRequest(
            sharing = StreamRequest.Sharing.SHARED,
            performance = StreamRequest.Performance.LOW_LATENCY,
            sampleRate = StreamRequest.UNSPECIFIED,
            framesPerCallback = StreamRequest.UNSPECIFIED,
            bufferBursts = StreamRequest.INITIAL_BUFFER_BURSTS,
        )

        /**
         * The one tool registered natively per stream. The native registry
         * only distinguishes empty from non-empty; which tool is immaterial.
         */
        val HOLDER = OutputTool.TEST_TONE

        /**
         * The native mute mask gates on any bit; the control thread owns the
         * reasons and sends one boolean, so one bit carries it.
         */
        const val MUTE_SLOT = 0

        const val EVENT_THREAD_NAME = "audio-events"

        /** How long [OpenStream.close] waits for the event thread to leave the library. */
        const val CLOSE_TIMEOUT_MILLIS = 1_000L

        /**
         * The event thread's wait per drain. Beats and errors post the wake
         * directly, so this bounds nothing the player feels; it is the
         * safety net for a wake that was never posted.
         */
        const val DRAIN_WAIT_MILLIS = 500

        const val UNKNOWN_DEVICE = "unknown output"
    }
}

/**
 * One native stream. Commands go straight into the bridge on the caller's
 * thread (the control thread); events come out on the [NativeStreamBackend.EVENT_THREAD_NAME]
 * thread, which blocks inside the bridge's drain and hands each one to the
 * listener, who re-posts to the control thread.
 */
private class NativeOpenStream(
    private val native: NativeCalls,
    private val handle: Long,
    opened: NativeEvent.Opened,
    private val listener: StreamListener,
    private val outputs: () -> List<RoutedDevice>,
    /** Runs first thing in [close], before the handle may go away. */
    private val onClosing: () -> Unit,
) : OpenStream, NativeBridge.Listener {
    override val sampleRate: Int = opened.sampleRate
    override val framesPerBurst: Int = opened.framesPerBurst
    private val deviceId = opened.deviceId

    @Volatile
    private var running = true
    private val drainer = Thread(::drainLoop, NativeStreamBackend.EVENT_THREAD_NAME)

    fun startDraining() = drainer.start()

    override fun routedDevice(): RoutedDevice? = outputs().firstOrNull { it.id == deviceId }

    override fun latencyMillis(): Int = native.nativeLatencyMillis(handle)

    override fun send(cmd: OutputCommand) {
        when (cmd) {
            is OutputCommand.VoiceOn -> native.nativeVoiceOn(handle, cmd.slot, cmd.midi, cmd.voice.ordinal, cmd.gain)
            is OutputCommand.VoiceOff -> native.nativeVoiceOff(handle, cmd.slot)
            is OutputCommand.VoiceGain -> native.nativeVoiceGain(handle, cmd.slot, cmd.gain)
            is OutputCommand.SetA4 -> native.nativeSetA4(handle, cmd.hz)
            is OutputCommand.Duck -> native.nativeDuck(handle, cmd.gain)
            is OutputCommand.MetroStart -> cmd.setup.let { s ->
                native.nativeMetroStart(
                    handle, s.bpm, s.beatsPerBar, s.beatUnit, s.compoundPulse, s.accentOrdinals(),
                    s.subdivision, s.click.ordinal, s.gain, s.barsOn, s.barsOff,
                )
            }
            is OutputCommand.MetroUpdate -> cmd.setup.let { s ->
                native.nativeMetroUpdate(
                    handle, s.bpm, s.beatsPerBar, s.beatUnit, s.compoundPulse, s.accentOrdinals(),
                    s.subdivision, s.click.ordinal, s.gain, s.barsOn, s.barsOff,
                )
            }
            OutputCommand.MetroStop -> native.nativeMetroStop(handle)
            // The registry and the mute set live in the control thread; a
            // stream only ever hears the one boolean through setMuted.
            is OutputCommand.Register,
            is OutputCommand.Unregister,
            is OutputCommand.Mute,
            -> Unit
        }
    }

    override fun setMuted(muted: Boolean) {
        native.nativeMute(handle, NativeStreamBackend.MUTE_SLOT, muted)
    }

    fun diagnostics(): NativeDiagnostics = NativeDiagnostics.decode(native.nativeDiagnostics(handle))

    override fun xrunCount(): Int = diagnostics().underruns.toInt()

    /** The render callback grows the buffer itself (decision 13); what it holds now is the answer. */
    override fun setBufferBursts(bursts: Int): Int = diagnostics().bufferBursts.toInt()

    override fun close(): Boolean {
        onClosing()
        running = false
        // Closes the native stream here, on the control thread, as the
        // AAudio guide asks, and wakes the event thread with the Closed event.
        native.nativeUnregister(handle, NativeStreamBackend.HOLDER.ordinal)
        drainer.join(NativeStreamBackend.CLOSE_TIMEOUT_MILLIS)
        if (drainer.isAlive) {
            // A thread may still be inside the library with this handle;
            // freeing it under that thread would be worse than the leak.
            return false
        }
        native.nativeDestroy(handle)
        return true
    }

    private fun drainLoop() {
        while (running) native.nativeDrainEvents(handle, this, NativeStreamBackend.DRAIN_WAIT_MILLIS)
    }

    override fun onEvent(kind: Int, a: Long, b: Long, c: Long, d: Long, tools: Int, ints: IntArray?) {
        when (val event = NativeEvent.decode(kind, a, b, c, d, tools, ints)) {
            is NativeEvent.Beat -> listener.onEvent(this, event.event)
            is NativeEvent.Counters -> listener.onEvent(this, event.event)
            // The native side parks itself on any stream error; the control
            // thread decides whether that is an unplug or a reopen in place.
            is NativeEvent.Parked -> listener.onDisconnected(this)
            // Opened and Closed are the control thread's own events, built
            // from its OutputInfo; the native side is never asked to resume,
            // and a Failed after open has no stream to belong to.
            is NativeEvent.Opened,
            NativeEvent.Closed,
            is NativeEvent.Failed,
            -> Unit
        }
    }

    private fun MetroSetup.accentOrdinals(): IntArray = IntArray(accents.size) { accents[it].ordinal }
}
