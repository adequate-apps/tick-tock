// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ticktock.core.catalogue.Catalogue
import ticktock.core.catalogue.EnsembleNote
import ticktock.core.catalogue.TuningNotes
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputInfo
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason
import ticktock.core.contracts.SoundingNotes
import ticktock.core.contracts.Voice
import ticktock.core.metro.Accent
import ticktock.core.metro.MetroSetup
import ticktock.core.music.Pitch
import ticktock.core.prefs.Prefs
import ticktock.engine.output.NativeDiagnostics
import ticktock.engine.output.MuteSource

data class OutputState(
    val droneWanted: Boolean = false,
    val metroWanted: Boolean = false,
    val droneSounding: Boolean = false,
    val metroSounding: Boolean = false,
    val info: OutputInfo? = null,
    val parked: ParkReason? = null,
    val failed: OutputEvent.Failed? = null,
    val bluetoothNotice: Boolean = false,
    val calibrating: Boolean = false,
    val muted: Boolean = false,
    val ringing: Boolean = false,
) {
    val toolRunning: Boolean get() = droneSounding || metroSounding || (calibrating && info != null)
}

data class CalibrationState(
    val clicks: Int = 0,
    val taps: Int = 0,
    val complete: Boolean = false,
    val offsetMs: Int? = null,
)

/** One owner across panes and Settings. Preferences survive; sounding intent belongs to this foreground session. */
class OutputSession(
    private val readPrefs: () -> Prefs,
    private val writePrefs: ((Prefs) -> Prefs) -> Unit,
    var makeConnection: () -> OutputConnection,
    private val changed: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val nanoTime: () -> Long = System::nanoTime,
) : SoundingNotes {
    private var connection: OutputConnection? = null
    private var prefs = readPrefs()
    private val mutableState = MutableStateFlow(OutputState())
    val state: StateFlow<OutputState> = mutableState
    private val visuals = BeatVisuals(scope, nanoTime)
    val beat: StateFlow<VisualBeat?> = visuals.state
    private val tempo = TapTempo()
    private var generation = 0
    private var batch = 0L
    private var pending = false
    private var lastDuck = 1f
    private var duckSequence = 0
    private var pendingDuck = 0
    private var duckSettled: (() -> Unit)? = null
    private var duckPresentation: Job? = null
    private var route: Pair<Int?, String>? = null
    private var dismissed: Pair<Int?, String>? = null
    private var closed = false
    private val calibrationState = MutableStateFlow<CalibrationState?>(null)
    val calibration: StateFlow<CalibrationState?> = calibrationState
    private var measuring: CalibrationRun? = null
    private var calibrationTimeout: Job? = null
    private var calibrationTail: Job? = null

    private data class CalibrationRun(
        val measurement: DelayMeasurement,
        val route: Pair<Int?, String>,
        val restoreDrone: Boolean,
        val restoreMetro: Boolean,
    )

    /** The debug build can observe the same stream without opening another engine. */
    var observeEvent: (OutputEvent) -> Unit = {}
    var observeCommand: (OutputCommand) -> Unit = {}
    /** Invalidates capture evidence immediately; the callback must never wait for analysis. */
    var bleedContextChanged: () -> Unit = {}

    @Synchronized
    override fun current(): IntArray = connection?.soundingNotes?.current() ?: IntArray(0)

    /** The app owns one drone voice; its root is the lowest sounding note, including root+fifth. */
    val soundingDroneMidi: Int? get() = current().minOrNull()

    @Synchronized
    fun diagnostics(): NativeDiagnostics? = connection?.diagnostics()

    fun selectedMidi(p: Prefs = readPrefs()): Int = p.droneMidi ?: tuning(p).defaultDrone.soundingMidi

    private fun tuning(p: Prefs) = TuningNotes.forInstrument(Catalogue.get(p.instrumentKey))

    @Synchronized
    fun startDrone() {
        if (closed || measuring != null || mutableState.value.droneWanted) return
        prefs = readPrefs()
        mutableState.value = mutableState.value.copy(droneWanted = true, parked = null, failed = null)
        send(listOf(OutputCommand.SetA4(prefs.a4Hz), OutputCommand.Register(OutputTool.DRONE), droneCommand()))
    }

    @Synchronized
    fun stopDrone() {
        if (!mutableState.value.droneWanted) return
        mutableState.value = mutableState.value.copy(droneWanted = false, droneSounding = false)
        send(listOf(OutputCommand.VoiceOff(DRONE_SLOT), OutputCommand.Unregister(OutputTool.DRONE)))
    }

    fun toggleDrone() = if (state.value.droneWanted) stopDrone() else startDrone()

    fun selectMidi(midi: Int) = writePrefs { it.copy(droneMidi = midi.coerceIn(Pitch.DETECTOR_WINDOW)) }
    fun ensemble(note: EnsembleNote) = selectMidi(tuning(readPrefs()).ensemble.getValue(note).soundingMidi)
    fun nextCheck() = selectMidi(tuning(readPrefs()).nextCheck(selectedMidi()).soundingMidi)
    fun octave(delta: Int) = selectMidi(selectedMidi() + delta * SEMITONES)
    fun voice(voice: Voice) = writePrefs { it.copy(droneVoice = voice) }
    fun droneVolume(delta: Float) = writePrefs { it.copy(droneGain = steppedGain(it.droneGain, delta)) }

    @Synchronized
    fun startMetro() {
        if (closed || measuring != null || mutableState.value.metroWanted) return
        prefs = readPrefs()
        mutableState.value = mutableState.value.copy(metroWanted = true, parked = null, failed = null)
        val setup = nextSetup()
        send(listOf(OutputCommand.Register(OutputTool.METRONOME), OutputCommand.MetroStart(setup)))
    }

    @Synchronized
    fun stopMetro() {
        if (!mutableState.value.metroWanted) return
        mutableState.value = mutableState.value.copy(metroWanted = false, metroSounding = false)
        clearBeat()
        send(listOf(OutputCommand.MetroStop, OutputCommand.Unregister(OutputTool.METRONOME)))
    }

    fun toggleMetro() = if (state.value.metroWanted) stopMetro() else startMetro()
    fun bpm(value: Int) = writePrefs { it.copy(metroBpm = value.coerceIn(MetroSetup.BPM_MIN, MetroSetup.BPM_MAX)) }
    fun stepBpm(delta: Int) = writePrefs { it.copy(metroBpm = (it.metroBpm + delta).coerceIn(MetroSetup.BPM_MIN, MetroSetup.BPM_MAX)) }
    fun tapTempo() { tempo.tap(nanoTime())?.let(::bpm) }
    fun preset(value: MetrePreset) = writePrefs(value::apply)
    fun customMetre(beats: Int, unit: Int) = writePrefs { MetrePresets.custom(it, beats, unit) }
    fun accent(index: Int) = writePrefs { p ->
        p.copy(metroAccents = p.metroAccents.mapIndexed { i, value ->
            if (i == index) Accent.entries[(value.ordinal + 1) % Accent.entries.size] else value
        })
    }

    /** Read the current store under this lock; a delayed listener cannot replay older preferences. */
    @Synchronized
    fun settingsChanged() {
        val previous = prefs
        prefs = readPrefs()
        val commands = mutableListOf<OutputCommand>()
        if (connection != null && previous.a4Hz != prefs.a4Hz) commands += OutputCommand.SetA4(prefs.a4Hz)
        if (state.value.droneWanted) {
            if (selectedMidi(previous) != selectedMidi(prefs) || previous.droneVoice != prefs.droneVoice) commands += droneCommand()
            else if (previous.droneGain != prefs.droneGain) commands += OutputCommand.VoiceGain(DRONE_SLOT, prefs.droneGain)
        }
        if (state.value.metroWanted && previous.metroSetup() != prefs.metroSetup()) commands += OutputCommand.MetroUpdate(nextSetup())
        if (previous.visualOffsetMs != prefs.visualOffsetMs || previous.bluetoothVisualOffsetMs != prefs.bluetoothVisualOffsetMs) {
            visuals.reset(generation, offset())
        }
        if (commands.isNotEmpty()) send(commands)
        changed()
    }

    @Synchronized
    fun duck(gain: Float) {
        cancelDuckEvidence()
        if ((measuring != null && gain != 1f) || gain == lastDuck || connection == null) return
        lastDuck = gain
        val command = OutputCommand.Duck(gain)
        observeCommand(command)
        connection?.send(listOf(command)) {}
    }

    /** A control-thread barrier cannot establish when a gain change reaches the microphone. */
    @Synchronized
    fun duck(gain: Float, settled: () -> Unit) {
        cancelDuckEvidence()
        if (closed || (measuring != null && gain != 1f)) return
        val link = connection
        if (link == null || link.port.info.value == null) {
            settled()
            return
        }
        duckSequence = if (duckSequence == Int.MAX_VALUE) 1 else duckSequence + 1
        pendingDuck = duckSequence
        duckSettled = settled
        lastDuck = gain
        val command = OutputCommand.Duck(gain, duckSequence)
        observeCommand(command)
        link.send(listOf(command)) {}
    }

    private fun cancelDuckEvidence() {
        pendingDuck = 0
        duckSettled = null
        duckPresentation?.cancel()
        duckPresentation = null
    }

    private fun invalidateBleed() {
        cancelDuckEvidence()
        bleedContextChanged()
    }

    private fun presented(event: OutputEvent.DuckSettled) {
        if (event.generation != pendingDuck || duckSettled == null) return
        duckPresentation?.cancel()
        duckPresentation = scope.launch {
            while (true) {
                val remaining = synchronized(this@OutputSession) {
                    if (closed || event.generation != pendingDuck) return@launch
                    event.nanos - nanoTime()
                }
                if (remaining <= 0) break
                delay((remaining + 999_999L) / 1_000_000L)
            }
            synchronized(this@OutputSession) {
                if (!closed && event.generation == pendingDuck) {
                    val settled = duckSettled
                    pendingDuck = 0
                    duckSettled = null
                    settled?.invoke()
                }
            }
        }
    }

    @Synchronized
    fun dismissBluetooth() {
        dismissed = route
        refresh()
    }

    @Synchronized
    fun beginCalibration() {
        val info = connection?.port?.info?.value ?: return
        if (closed || measuring != null || !info.wireless) return
        val restoreDrone = state.value.droneWanted
        val restoreMetro = state.value.metroWanted
        clearBeat()
        val run = CalibrationRun(DelayMeasurement(generation), info.deviceId to info.deviceName, restoreDrone, restoreMetro)
        measuring = run
        duck(1f)
        calibrationState.value = CalibrationState()
        mutableState.value = state.value.copy(droneWanted = false, metroWanted = false, calibrating = true)
        // Keep the route open while the drone yields to the finite click run.
        send(listOf(OutputCommand.Register(OutputTool.METRONOME), OutputCommand.VoiceOff(DRONE_SLOT),
            OutputCommand.Unregister(OutputTool.DRONE), OutputCommand.MetroStart(run.measurement.setup())))
        armCalibrationTimeout(run)
    }

    fun calibrationTap() = calibrationTap(0)

    /** Contact age bridges pointer uptime to the native monotonic clock without dispatch or hold bias. */
    @Synchronized
    fun calibrationTap(eventAgeMillis: Long) {
        val run = measuring ?: return
        val contactNanos = nanoTime() - eventAgeMillis.coerceAtLeast(0) * 1_000_000L
        if (calibrationState.value?.complete == true || !run.measurement.tap(contactNanos)) return
        updateCalibration(run)
    }

    @Synchronized
    fun retryCalibration() {
        val previous = measuring ?: return
        if (calibrationState.value?.complete != true) return
        calibrationTimeout?.cancel()
        calibrationTail?.cancel()
        calibrationTail = null
        clearBeat()
        val run = previous.copy(measurement = DelayMeasurement(generation))
        measuring = run
        calibrationState.value = CalibrationState()
        mutableState.value = state.value.copy(calibrating = true)
        send(listOf(OutputCommand.MetroStart(run.measurement.setup())))
        armCalibrationTimeout(run)
    }

    @Synchronized
    fun useCalibration() {
        val offset = calibrationState.value?.takeIf { it.complete }?.offsetMs ?: return
        val run = measuring ?: return
        val info = connection?.port?.info?.value ?: return
        if (!info.wireless || run.route != (info.deviceId to info.deviceName)) return
        writePrefs { it.copy(bluetoothVisualOffsetMs = offset) }
        finishCalibration(restore = true)
    }

    @Synchronized
    fun cancelCalibration() = finishCalibration(restore = true)

    private fun armCalibrationTimeout(run: CalibrationRun) {
        calibrationTimeout = scope.launch {
            delay(DelayMeasurement.TIMEOUT_MILLIS)
            synchronized(this@OutputSession) {
                if (measuring === run && calibrationState.value?.complete != true) {
                    calibrationState.value = CalibrationState(run.measurement.clicksReceived, run.measurement.tapsReceived, complete = true)
                    mutableState.value = state.value.copy(calibrating = false)
                    send(listOf(OutputCommand.MetroStop))
                }
            }
        }
    }

    private fun updateCalibration(run: CalibrationRun) {
        val measurement = run.measurement
        calibrationState.value = CalibrationState(measurement.clicksReceived, measurement.tapsReceived)
        val median = measurement.medianMs() ?: return
        if (calibrationTail != null) return
        calibrationTail = scope.launch {
            val tailStart = maxOf(measurement.lastClickNanos!!, measurement.lastTapNanos!!)
            val remaining = tailStart + DelayMeasurement.CLICK_TAIL_NANOS - nanoTime()
            if (remaining > 0) delay((remaining + DelayMeasurement.NANOS_PER_MILLI - 1) / DelayMeasurement.NANOS_PER_MILLI)
            synchronized(this@OutputSession) {
                if (measuring === run) {
                    calibrationTimeout?.cancel()
                    calibrationState.value = CalibrationState(measurement.clicksReceived, measurement.tapsReceived, true, median)
                    mutableState.value = state.value.copy(calibrating = false)
                }
            }
        }
    }

    private fun finishCalibration(restore: Boolean) {
        val run = measuring ?: return
        measuring = null
        calibrationTimeout?.cancel()
        calibrationTail?.cancel()
        calibrationTail = null
        calibrationState.value = null
        val info = connection?.port?.info?.value
        val sameRoute = info != null && run.route == (info.deviceId to info.deviceName)
        val drone = restore && sameRoute && run.restoreDrone
        val metro = restore && sameRoute && run.restoreMetro
        prefs = readPrefs()
        mutableState.value = state.value.copy(droneWanted = drone, metroWanted = metro, calibrating = false)
        clearBeat()
        val commands = mutableListOf<OutputCommand>(OutputCommand.MetroStop)
        if (drone) commands += listOf(OutputCommand.SetA4(prefs.a4Hz), OutputCommand.Register(OutputTool.DRONE), droneCommand())
        else commands += listOf(OutputCommand.VoiceOff(DRONE_SLOT), OutputCommand.Unregister(OutputTool.DRONE))
        if (metro) commands += listOf(OutputCommand.Register(OutputTool.METRONOME), OutputCommand.MetroStart(nextSetup()))
        else commands += OutputCommand.Unregister(OutputTool.METRONOME)
        send(commands)
    }

    @Synchronized
    fun background() {
        finishCalibration(restore = false)
        stopMetro()
        stopDrone()
        duck(1f)
        connection?.background()
        mutableState.value = mutableState.value.copy(parked = ParkReason.BACKGROUND)
        clearBeat()
        changed()
    }

    @Synchronized
    fun foreground() { connection?.foreground() }

    @Synchronized
    fun close() {
        if (closed) return
        background()
        closed = true
        connection?.close()
        scope.cancel()
    }

    private fun droneCommand() = OutputCommand.VoiceOn(DRONE_SLOT, selectedMidi(prefs), prefs.droneVoice, prefs.droneGain)

    private fun nextSetup(): MetroSetup {
        generation++
        visuals.reset(generation, offset())
        return prefs.metroSetup().copy(generation = generation)
    }

    private fun clearBeat() {
        generation++
        visuals.reset(generation, offset())
    }

    private fun offset(): Int = if (connection?.port?.info?.value?.wireless == true) {
        prefs.bluetoothVisualOffsetMs ?: 0
    } else prefs.visualOffsetMs

    private fun ensureConnection(): OutputConnection {
        connection?.let { return it }
        return makeConnection().also { fresh ->
            connection = fresh
            // The native stream replays no events. Subscribe before sending its first registration.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { fresh.port.events.collect(::event) }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fresh.port.info.collect { synchronized(this@OutputSession) { refresh() } }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fresh.controlDiagnostics?.map { it.muteSources }?.distinctUntilChanged()?.collect {
                    // The control thread publishes diagnostics before updating its sounding-note snapshot.
                    fresh.send(emptyList()) {
                        scope.launch { synchronized(this@OutputSession) { if (!closed) refresh() } }
                    }
                }
            }
        }
    }

    private fun send(commands: List<OutputCommand>) {
        if (commands.any { it is OutputCommand.VoiceOn || it is OutputCommand.VoiceOff ||
                it is OutputCommand.VoiceGain || it is OutputCommand.SetA4 }) invalidateBleed()
        val link = ensureConnection()
        val version = ++batch
        pending = true
        commands.forEach(observeCommand)
        link.send(commands) {
            scope.launch {
                synchronized(this@OutputSession) {
                    if (version != batch || closed) return@synchronized
                    pending = false
                    val reason = state.value.parked
                    if (link.port.info.value == null && reason != null && reason != ParkReason.HEADPHONES_DISCONNECTED) {
                        mutableState.value = state.value.copy(droneWanted = false, metroWanted = false)
                    }
                    refresh()
                }
            }
        }
    }

    @Synchronized
    private fun event(event: OutputEvent) {
        observeEvent(event)
        when (event) {
            is OutputEvent.DuckSettled -> presented(event)
            is OutputEvent.Beat -> {
                val run = measuring
                if (run != null && calibrationState.value?.complete != true && run.measurement.beat(event)) updateCalibration(run)
                else if (state.value.metroWanted && connection?.port?.info?.value != null) visuals.accept(event)
            }
            is OutputEvent.Parked -> if (connection?.port?.info?.value == null) {
                val retain = pending || event.reason == ParkReason.HEADPHONES_DISCONNECTED
                mutableState.value = state.value.copy(
                    parked = event.reason,
                    droneWanted = state.value.droneWanted && (retain || OutputTool.DRONE !in event.tools),
                    metroWanted = state.value.metroWanted && (retain || OutputTool.METRONOME !in event.tools),
                )
                // Resumption keeps the generator generation, so only queued visual work is cancelled.
                visuals.reset(generation, offset())
                refresh()
            }
            is OutputEvent.Failed -> if (connection?.port?.info?.value == null) {
                mutableState.value = state.value.copy(failed = event, parked = ParkReason.OUTPUT_FAILED)
                refresh()
            }
            is OutputEvent.Opened, is OutputEvent.Resumed, OutputEvent.Closed -> {
                invalidateBleed()
                refresh()
            }
            is OutputEvent.Counters -> Unit
        }
    }

    private fun refresh() {
        val info = connection?.port?.info?.value
        val nextRoute = info?.let { it.deviceId to it.deviceName }
        val muteSources = connection?.controlDiagnostics?.value?.muteSources.orEmpty()
        val muted = muteSources.isNotEmpty()
        if (nextRoute != route || muted != state.value.muted) invalidateBleed()
        if (measuring?.let { it.route != nextRoute } == true) {
            finishCalibration(restore = false)
            return
        }
        if (measuring != null && muted) {
            finishCalibration(restore = true)
            return
        }
        if (nextRoute != route) {
            route = nextRoute
            dismissed = null
            visuals.reset(generation, offset())
        }
        mutableState.value = state.value.copy(
            info = info,
            droneSounding = state.value.droneWanted && info != null && !muted && current().isNotEmpty(),
            metroSounding = state.value.metroWanted && info != null && !muted,
            muted = muted,
            ringing = MuteSource.CALL_RINGING in muteSources,
            parked = if (info != null) null else state.value.parked,
            failed = if (info != null) null else state.value.failed,
            bluetoothNotice = info?.wireless == true && route != dismissed,
        )
        changed()
    }

    companion object {
        const val DRONE_SLOT = 0
        const val SEMITONES = 12
    }
}
