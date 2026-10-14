// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import ticktock.core.contracts.ForegroundOwner
import ticktock.core.contracts.NoForegroundOwner
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputInfo
import ticktock.core.contracts.OutputPort
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason
import ticktock.core.contracts.RouteClass
import ticktock.engine.output.MuteReasons
import ticktock.engine.output.MuteSource
import ticktock.engine.output.OpenStream
import ticktock.engine.output.OutputRegistry
import ticktock.engine.output.OutputSoundingNotes
import ticktock.engine.output.RouteClassification
import ticktock.engine.output.RouteClassifier
import ticktock.engine.output.RoutedDevice
import ticktock.engine.output.StreamBackend
import ticktock.engine.output.StreamListener
import ticktock.engine.output.StreamOpenFailure
import ticktock.engine.output.StreamRequest
import ticktock.engine.output.ToolState

/**
 * The one owner of the output stream, and the app's only door to it. Every
 * command is posted to the `audio-control` thread and the caller returns at
 * once (OUT-15); the thread opens the stream on the first registered tool,
 * closes it on the last (OUT-01), parks and restarts the tools around
 * unplugs (OUT-03, OUT-05, OUT-06), owns the mute set (OUT-10), applies the
 * background policy (OUT-11), watches the audio mode for calls (CAL-01,
 * CAL-02) and classifies the route from the routed device (OUT-12).
 *
 * The platform's own callbacks (device changes, mode changes, stream
 * disconnects) are re-posted here, so every piece of state below is touched
 * by this one thread and needs no lock. The stream itself is reached only
 * through [StreamBackend]; the JNI bridge implements that seam.
 */
class AudioControlThread(
    private val backend: StreamBackend,
    private val devices: OutputDevices,
    modes: AudioModes,
    foregroundOwner: ForegroundOwner = NoForegroundOwner,
    private val backgroundPolicy: BackgroundPolicy = BackgroundPolicy.StopEverything,
    apiLevel: Int = Build.VERSION.SDK_INT,
) : OutputPort {
    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)

    /** The control thread's looper, so a harness can drain it on demand. */
    val looper: Looper get() = thread.looper

    private val registry = OutputRegistry(foregroundOwner)
    private val muteReasons = MuteReasons()
    private val toolState = ToolState()
    private val callWatcher = CallWatcher(modes, handler, apiLevel, ::onCallState)

    /** What the stream is sounding, for the bleed policy on the analysis thread (POL-24). */
    val soundingNotes = OutputSoundingNotes()

    private var stream: OpenStream? = null

    /** Tools parked by an unplug, waiting for a headphone-class route to return (OUT-05). */
    private var waitingForRoute: Set<OutputTool>? = null
    private var bufferBursts = StreamRequest.INITIAL_BUFFER_BURSTS
    private var lastXruns = 0

    private val eventFlow = MutableSharedFlow<OutputEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<OutputEvent> get() = eventFlow
    override val info = MutableStateFlow<OutputInfo?>(null)

    /** The route in finer grain than [OutputInfo] carries: the Bluetooth banner reads the wireless flag. */
    val route: StateFlow<RouteClassification?> get() = routeFlow
    private val routeFlow = MutableStateFlow<RouteClassification?>(null)

    val diagnostics: StateFlow<ControlDiagnostics> get() = diagnosticsFlow
    private val diagnosticsFlow = MutableStateFlow(ControlDiagnostics())

    /** Begins watching devices and the audio mode. Call once; the stream still waits for the first tool. */
    fun start() {
        handler.post {
            devices.watch(handler, deviceListener)
            callWatcher.start()
        }
    }

    /**
     * Closes whatever is open and ends the thread; the instance is finished
     * afterwards. The quit is the safe kind, so the cleanup posted here and
     * every command sent before it still run.
     */
    fun shutdown() {
        handler.post {
            callWatcher.stop()
            devices.unwatch()
            registry.clear { closeStream() }
        }
        thread.quitSafely()
    }

    override fun send(cmd: OutputCommand) {
        handler.post { handle(cmd) }
    }

    /** The app left the foreground: the policy says what keeps sounding; in 2.0, nothing. */
    fun appBackgrounded() {
        handler.post { background() }
    }

    /** The app is back. Nothing restarts on its own (decision 4); the player starts the tools. */
    fun appForegrounded() = Unit

    // --- commands --------------------------------------------------------------

    private fun handle(cmd: OutputCommand) {
        when (cmd) {
            is OutputCommand.Register -> register(cmd.tool)
            is OutputCommand.Unregister -> unregister(cmd.tool)
            is OutputCommand.Mute -> setMute(MuteSource.of(cmd.reason), cmd.active)
            is OutputCommand.VoiceOn,
            is OutputCommand.VoiceOff,
            is OutputCommand.VoiceGain,
            is OutputCommand.SetA4,
            is OutputCommand.Duck,
            is OutputCommand.MetroStart,
            is OutputCommand.MetroUpdate,
            OutputCommand.MetroStop,
            -> {
                toolState.apply(cmd)
                stream?.send(cmd)
                updateSounding()
            }
        }
    }

    private fun register(tool: OutputTool) {
        var opened = true
        val transition = registry.register(tool) { opened = open(resumed = null) }
        if (!opened) {
            parkAll(ParkReason.OUTPUT_FAILED)
            return
        }
        if (!transition && stream == null) {
            // Registered tools with no stream means an unplug parked them. The
            // player starting a tool now is the decision that the route in hand
            // is fine, so the parked set comes back with it on that route.
            val parked = waitingForRoute.orEmpty() - tool
            if (!open(resumed = parked)) parkAll(ParkReason.OUTPUT_FAILED)
        }
    }

    private fun unregister(tool: OutputTool) {
        registry.unregister(tool) {
            closeStream()
            emit(OutputEvent.Closed)
        }
        // A tool switched off while unplugged stays off when the route returns (OUT-06).
        waitingForRoute = waitingForRoute?.minus(tool)
        if (registry.isEmpty) {
            waitingForRoute = null
            toolState.clear()
        }
    }

    private fun setMute(source: MuteSource, on: Boolean) {
        val changed = muteReasons.set(source, on)
        diagnosticsFlow.update { it.copy(muteSources = muteReasons.sources) }
        if (!changed) return
        stream?.setMuted(muteReasons.muted)
        updateSounding()
    }

    private fun background() {
        val registered = registry.registered
        // A policy can only keep what is registered, however it answers.
        val keep = backgroundPolicy.keepsSounding(registered) intersect registered
        val park = registered - keep
        if (park.isEmpty()) return
        if (keep.isEmpty()) {
            parkAll(ParkReason.BACKGROUND)
            return
        }
        // A policy that keeps some tools is v1.1's, under its foreground
        // service; the registry side of it is already in place.
        park.forEach { registry.unregister(it) { closeStream() } }
        waitingForRoute = waitingForRoute?.minus(park)
        emit(OutputEvent.Parked(ParkReason.BACKGROUND, park))
    }

    // --- the stream ------------------------------------------------------------

    /**
     * Opens the stream and rebuilds the tools in it. [resumed] names the tools
     * an unplug had parked, for the Resumed event; null means a fresh open.
     * Returns false after reporting the failure (OUT-17).
     */
    private fun open(resumed: Set<OutputTool>?): Boolean {
        val request = StreamRequest(bufferBursts = StreamRequest.INITIAL_BUFFER_BURSTS)
        val opened = backend.open(request, streamListener).getOrElse { failure ->
            val refusal = failure as? StreamOpenFailure
            emit(OutputEvent.Failed(refusal?.deviceName ?: UNKNOWN_DEVICE, refusal?.refusedRates.orEmpty()))
            return false
        }
        stream = opened
        bufferBursts = request.bufferBursts
        lastXruns = 0
        waitingForRoute = null
        opened.setMuted(muteReasons.muted)
        toolState.replay().forEach(opened::send)
        val current = describe(opened)
        publish(current)
        emit(if (resumed == null) OutputEvent.Opened(current) else OutputEvent.Resumed(resumed, current))
        updateSounding()
        diagnosticsFlow.update { it.copy(bufferBursts = bufferBursts, xruns = 0) }
        handler.postDelayed(xrunPoll, XRUN_POLL_MILLIS)
        return true
    }

    private fun closeStream() {
        val open = stream ?: return
        stream = null
        handler.removeCallbacks(xrunPoll)
        // The handle is dropped whether or not the platform finished: a stop
        // that hangs must never leave output dead for the rest of the session.
        if (!open.close()) log("close timed out; the stream handle was dropped")
        publish(null)
        updateSounding()
    }

    /**
     * Parks the registered tools. An unplug keeps them registered and waits
     * for the route; every other reason drops them, so nothing restarts until
     * the player acts (decisions 4 and 12).
     */
    private fun parkAll(reason: ParkReason) {
        when (reason) {
            ParkReason.HEADPHONES_DISCONNECTED -> {
                val tools = registry.registered
                closeStream()
                if (tools.isEmpty()) return
                waitingForRoute = tools
                emit(OutputEvent.Parked(reason, tools))
            }
            ParkReason.INCOMING_CALL,
            ParkReason.BACKGROUND,
            ParkReason.OUTPUT_FAILED,
            -> {
                waitingForRoute = null
                val dropped = registry.clear { closeStream() }
                toolState.clear()
                if (dropped.isNotEmpty()) emit(OutputEvent.Parked(reason, dropped))
            }
        }
    }

    private fun streamDisconnected() {
        val open = stream ?: return
        val routed = open.routedDevice()
        val routeStillPresent = routed != null && devices.outputs().any { it.id == routed.id }
        if (!routeStillPresent) {
            parkAll(ParkReason.HEADPHONES_DISCONNECTED)
            return
        }
        // The device is still there, so the platform moved the route to a new
        // primary device or restarted the stream: reopen in place, possibly at
        // another rate (OUT-13).
        closeStream()
        log("stream ended with ${routed.name} present; reopened")
        if (!open(resumed = registry.registered)) parkAll(ParkReason.OUTPUT_FAILED)
    }

    private fun routingChanged() {
        val open = stream ?: return
        val current = describe(open)
        publish(current)
        log("route is now ${current.deviceName} (${current.routeClass}), ${current.latencyMillis} ms")
    }

    private fun describe(open: OpenStream): OutputInfo {
        val routed = open.routedDevice()
        val classification = RouteClassifier.classify(routed)
        routeFlow.value = classification
        return OutputInfo(
            sampleRate = open.sampleRate,
            framesPerBlock = open.framesPerBurst,
            routeClass = classification.routeClass,
            deviceName = routed?.name ?: UNKNOWN_DEVICE,
            latencyMillis = open.latencyMillis(),
        )
    }

    private fun publish(current: OutputInfo?) {
        info.value = current
        if (current == null) routeFlow.value = null
    }

    private fun updateSounding() {
        val audible = stream != null && !muteReasons.muted
        soundingNotes.update(if (audible) toolState.soundingMidi() else IntArray(0))
    }

    private val xrunPoll = object : Runnable {
        override fun run() {
            val open = stream ?: return
            val count = open.xrunCount()
            if (count > lastXruns) {
                lastXruns = count
                if (bufferBursts < StreamRequest.MAX_BUFFER_BURSTS) {
                    val granted = open.setBufferBursts(bufferBursts + 1)
                    log("$count underruns: buffer $bufferBursts to $granted bursts")
                    bufferBursts = granted
                }
                diagnosticsFlow.update { it.copy(bufferBursts = bufferBursts, xruns = count) }
            }
            handler.postDelayed(this, XRUN_POLL_MILLIS)
        }
    }

    // --- outside events --------------------------------------------------------

    private fun onCallState(state: CallState) {
        diagnosticsFlow.update { it.copy(callState = state) }
        when (state) {
            CallState.RINGING -> setMute(MuteSource.CALL_RINGING, true)
            CallState.ANSWERED -> parkAll(ParkReason.INCOMING_CALL)
            CallState.NONE -> setMute(MuteSource.CALL_RINGING, false)
        }
    }

    private val deviceListener = object : OutputDevices.Listener {
        override fun onAdded(devices: List<RoutedDevice>) {
            val parked = waitingForRoute ?: return
            val privateRoute = devices.any { RouteClassifier.classify(it).routeClass == RouteClass.HEADPHONES }
            if (!privateRoute) return
            if (!open(resumed = parked)) parkAll(ParkReason.OUTPUT_FAILED)
        }

        override fun onRemoved(devices: List<RoutedDevice>) {
            val routed = stream?.routedDevice() ?: return
            if (devices.any { it.id == routed.id }) parkAll(ParkReason.HEADPHONES_DISCONNECTED)
        }

        override fun onBecomingNoisy() {
            if (stream != null) parkAll(ParkReason.HEADPHONES_DISCONNECTED)
        }
    }

    /** Re-posts to the control thread and ignores a stream that is no longer the current one. */
    private val streamListener = object : StreamListener {
        override fun onDisconnected(stream: OpenStream) {
            handler.post { if (stream === this@AudioControlThread.stream) streamDisconnected() }
        }

        override fun onRoutingChanged(stream: OpenStream) {
            handler.post { if (stream === this@AudioControlThread.stream) routingChanged() }
        }

        override fun onEvent(stream: OpenStream, event: OutputEvent) {
            handler.post { if (stream === this@AudioControlThread.stream) emit(event) }
        }
    }

    private fun emit(event: OutputEvent) {
        eventFlow.tryEmit(event)
    }

    private fun log(line: String) {
        diagnosticsFlow.update { it.logged(line) }
    }

    companion object {
        const val THREAD_NAME = "audio-control"
        const val UNKNOWN_DEVICE = "unknown output"

        /** How often the underrun count is read while a stream is open (SHM-02). */
        const val XRUN_POLL_MILLIS = 1_000L
        private const val EVENT_BUFFER = 256
    }
}
