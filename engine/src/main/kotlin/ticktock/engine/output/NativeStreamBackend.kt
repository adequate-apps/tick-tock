// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason
import ticktock.core.metro.MetroSetup
import ticktock.engine.NativeBridge
import ticktock.engine.NativeCalls

/**
 * The [StreamBackend] over the JNI bridge: the one the control thread uses
 * on a device.
 *
 * One native handle carries one engine: the render engine with its voices
 * and its beat generator, and the native controller around it. A close
 * destroys the handle, so the next open starts from a fresh engine. A park
 * keeps the handle with the native stream closed and the engine's state
 * intact, and the next open resumes it: the native side reopens for the
 * same engine and converts the generator's anchor, which is where MET-09
 * and OUT-13 are implemented. One fixed [HOLDER] registration keeps the
 * native registry non-empty for the life of the handle, which is all the
 * native side needs from it; the Kotlin registry in the control thread is
 * the only registry that decides anything.
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

    /** A handle whose stream is parked, kept for the next open to resume; 0 when none. Control thread only. */
    private var parked = 0L

    /** The native counters of the open stream, or null while none is open. */
    fun diagnostics(): NativeDiagnostics? = current?.diagnostics()

    override fun open(request: StreamRequest, listener: StreamListener): Result<OpenStream> {
        if (request != HONOURED) {
            return Result.failure(IllegalArgumentException("the bridge opens $HONOURED only; asked for $request"))
        }
        val resuming = parked != 0L
        val handle = if (resuming) parked else native.nativeCreate()
        parked = 0L
        // Either call opens the stream inside the native registry, so its
        // verdict is already queued: one drain with no wait reads it. A
        // resumed handle may still hold the park it was left with, or beats
        // from before it; the verdict is the first open outcome after them.
        if (resuming) native.nativeResume(handle) else native.nativeRegister(handle, HOLDER.ordinal)
        val verdict = object : NativeBridge.Listener {
            var outcome: NativeEvent? = null
            override fun onEvent(kind: Int, a: Long, b: Long, c: Long, d: Long, tools: Int, ints: IntArray?) {
                if (outcome != null) return
                val event = NativeEvent.decode(kind, a, b, c, d, tools, ints)
                if (event is NativeEvent.Opened || event is NativeEvent.Failed) outcome = event
            }
        }
        native.nativeDrainEvents(handle, verdict, 0)
        return when (val outcome = verdict.outcome) {
            is NativeEvent.Opened -> {
                val stream = NativeOpenStream(native, handle, outcome, listener, outputs, ::endStream)
                current = stream
                stream.startDraining()
                Result.success(stream)
            }
            is NativeEvent.Failed -> {
                destroy(handle)
                Result.failure(StreamOpenFailure(deviceName(outcome.deviceId), outcome.refusedRates))
            }
            else -> {
                destroy(handle)
                Result.failure(StreamOpenFailure(UNKNOWN_DEVICE, emptyList()))
            }
        }
    }

    override fun discard() {
        val handle = parked
        parked = 0L
        if (handle != 0L) destroy(handle)
    }

    /** The stream's last act: it is no longer current, and its handle meets its fate. */
    private fun endStream(handle: Long, fate: HandleFate) {
        current = null
        when (fate) {
            HandleFate.KEEP -> parked = handle
            HandleFate.DESTROY -> destroy(handle)
            HandleFate.LEAK -> Unit
        }
    }

    private fun destroy(handle: Long) {
        native.nativeDestroy(handle)
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
         * The one tool registered natively per handle. The native registry
         * only distinguishes empty from non-empty; which tool is immaterial.
         */
        val HOLDER = OutputTool.TEST_TONE

        /**
         * The native mute mask gates on any bit; the control thread owns the
         * reasons and sends one boolean, so one bit carries it.
         */
        const val MUTE_SLOT = 0

        const val EVENT_THREAD_NAME = "audio-events"

        /** How long [OpenStream.close] and [OpenStream.park] wait for the event thread to leave the library. */
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

/** What becomes of a native handle when its stream ends. */
private enum class HandleFate {
    /** Kept for the next open to resume. */
    KEEP,
    DESTROY,
    /** A thread may still be inside the library with it: neither freed nor reused. */
    LEAK,
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
    /** Runs when the stream ends, with what becomes of its handle. */
    private val onEnded: (handle: Long, fate: HandleFate) -> Unit,
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

    override fun close(): Boolean = end(keep = false)

    override fun park(): Boolean = end(keep = true)

    private fun end(keep: Boolean): Boolean {
        running = false
        // Closes the native stream here, on the control thread, as the AAudio
        // guide asks. A park leaves the holder registered so the native
        // controller keeps the engine for resume(); the native side may
        // already have parked itself after a stream error, in which case the
        // call is nothing. Either call wakes the event thread.
        if (keep) {
            native.nativePark(handle, ParkReason.HEADPHONES_DISCONNECTED.ordinal)
        } else {
            native.nativeUnregister(handle, NativeStreamBackend.HOLDER.ordinal)
        }
        drainer.join(NativeStreamBackend.CLOSE_TIMEOUT_MILLIS)
        if (drainer.isAlive) {
            // A thread may still be inside the library with this handle;
            // freeing it, or resuming it, under that thread would be worse
            // than the leak.
            onEnded(handle, HandleFate.LEAK)
            return false
        }
        onEnded(handle, if (keep) HandleFate.KEEP else HandleFate.DESTROY)
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
            // from its OutputInfo, and a Failed after open has no stream to
            // belong to.
            is NativeEvent.Opened,
            NativeEvent.Closed,
            is NativeEvent.Failed,
            -> Unit
        }
    }

    private fun MetroSetup.accentOrdinals(): IntArray = IntArray(accents.size) { accents[it].ordinal }
}
