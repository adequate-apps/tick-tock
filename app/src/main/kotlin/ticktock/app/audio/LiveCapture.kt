// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.audio

import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ticktock.core.contracts.AudioInput
import ticktock.core.contracts.InputException
import ticktock.core.contracts.InputFailure
import ticktock.core.contracts.InputInfo
import ticktock.core.detect.AnalysisCounters
import ticktock.core.detect.Decision
import ticktock.core.detect.Detector
import ticktock.core.detect.HopAnalyser
import ticktock.core.detect.ResponseMode
import ticktock.core.detect.YinEstimator
import ticktock.core.music.Cents
import ticktock.core.music.Pitch
import ticktock.engine.input.AnalysisThread
import ticktock.engine.input.InputListener
import ticktock.engine.input.RecordingState
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * What the readout shows after the newest hop. Values hold while the note
 * holds (POL-02), and they change only when what the readout prints changes:
 * [hz] is kept to the tenth the readout shows, so the sub-tenth motion of a
 * held note moves nothing (POL-31).
 */
data class Readout(
    /** Spelling-neutral note label, or null for a blank readout. */
    val note: String? = null,
    /** Whole cents as the UI prints them, null when the readout is blank. */
    val cents: Int? = null,
    /** Frequency to the tenth of a hertz, null when the readout is blank. */
    val hz: Double? = null,
) {
    companion object {
        private const val TENTHS = 10.0

        fun shownHz(hz: Double): Double = (hz * TENTHS).roundToLong() / TENTHS
    }
}

/** The newest hop's level, in dBFS after mic gain, floored at [LEVEL_FLOOR_DB]. The one thing that moves at hop rate (POL-31). */
data class Level(val hop: Long = -1, val db: Double = LEVEL_FLOOR_DB) {
    companion object {
        const val LEVEL_FLOOR_DB = -90.0
        private const val DB_PER_DECADE = 20.0

        fun db(level: Double): Double =
            if (level <= 0.0) LEVEL_FLOOR_DB else max(LEVEL_FLOOR_DB, DB_PER_DECADE * log10(level))
    }
}

/** The analysis counters and the cost of the last hop, sampled about once a second (POL-18, DET-17). */
data class CaptureDiagnostics(
    val counters: AnalysisCounters = AnalysisCounters(),
    val lastHopNanos: Long = 0,
)

/** The capture session as a screen sees it: one object, one state flow that moves only when the readout does. */
data class CaptureState(
    val info: InputInfo? = null,
    /** Why the stream is stopped, null while it runs or before it started. */
    val failure: InputFailure? = null,
    /** The saved interface that was absent at open, for the status sentence (IN-03). */
    val absentDevice: InputFailure.Disconnected? = null,
    val recording: RecordingState? = null,
    val readout: Readout = Readout(),
) {
    val isListening: Boolean get() = info != null && failure == null
}

/**
 * Wires the M1 detector to an [AudioInput] on the analysis thread and folds
 * what a screen wants into three flows: [state], which moves when the
 * readout or the stream does and otherwise holds; [level], which moves
 * every hop for the meter and for nothing else; and [diagnostics], sampled
 * every [DIAGNOSTICS_HOPS] hops. A screen that observes [state] alone is
 * redrawn when the note, the cents or the shown tenth of a hertz change,
 * and a held note redraws nothing (POL-31). Pass this object as the input's
 * [InputListener] so stream failures and recoveries land in the same state.
 */
class LiveCapture(
    mode: ResponseMode = ResponseMode.NORMAL,
    /** The A4 every reading is measured against (POL-19); the saved value in M4, a launch extra until then. */
    a4Hz: Double = Pitch.A4_DEFAULT_HZ,
    private val promote: () -> Unit = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
) : InputListener {
    private val detector = Detector(mode).also { it.setA4(a4Hz) }
    val a4Hz: Double get() = detector.a4.hz
    private val thread = AnalysisThread(HopAnalyser(detector, YinEstimator(detector)), promote, ::onDecisions)
    private val stateFlow = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = stateFlow
    private val levelFlow = MutableStateFlow(Level())
    val level: StateFlow<Level> = levelFlow
    private val diagnosticsFlow = MutableStateFlow(CaptureDiagnostics())
    val diagnostics: StateFlow<CaptureDiagnostics> = diagnosticsFlow

    /** Hops since the diagnostics were last sampled; the analysis thread's alone. */
    private var hopsSinceDiagnostics = 0

    fun start(input: AudioInput) {
        val result = thread.start(input)
        hopsSinceDiagnostics = 0
        levelFlow.value = Level()
        diagnosticsFlow.value = CaptureDiagnostics()
        stateFlow.value = result.fold(
            onSuccess = { info -> CaptureState(info = info, absentDevice = stateFlow.value.absentDevice) },
            onFailure = { error ->
                val failure = (error as? InputException)?.failure
                    ?: InputFailure.Refused(UNKNOWN_DEVICE, emptyList())
                CaptureState(failure = failure)
            },
        )
    }

    fun stop() {
        thread.stop()
        stateFlow.value = CaptureState()
        levelFlow.value = Level()
        diagnosticsFlow.value = CaptureDiagnostics()
    }

    /** The platform's recording report, from the source's flow; the screen forwards it here. */
    fun recordingReported(recording: RecordingState?) {
        stateFlow.value = stateFlow.value.copy(recording = recording)
    }

    override fun onStopped(failure: InputFailure) {
        stateFlow.value = stateFlow.value.copy(info = null, failure = failure, recording = null)
    }

    override fun onResumed(info: InputInfo) {
        stateFlow.value = stateFlow.value.copy(info = info, failure = null)
    }

    override fun onDeviceAbsent(failure: InputFailure.Disconnected) {
        stateFlow.value = stateFlow.value.copy(absentDevice = failure)
    }

    private fun onDecisions(decisions: List<Decision>) {
        val newest = decisions.last()
        levelFlow.value = Level(newest.hop, Level.db(newest.level))
        val previous = stateFlow.value
        val readout = previous.readout.after(newest)
        // Equal values would conflate in the flow anyway; the comparison
        // saves the copy on the hops, most of them, that change nothing.
        if (readout != previous.readout) stateFlow.value = previous.copy(readout = readout)
        hopsSinceDiagnostics += decisions.size
        if (hopsSinceDiagnostics >= DIAGNOSTICS_HOPS) {
            hopsSinceDiagnostics = 0
            diagnosticsFlow.value = CaptureDiagnostics(thread.counters, thread.lastHopNanos)
        }
    }

    private fun Readout.after(decision: Decision): Readout {
        val note = decision.incumbent?.let(Pitch::label)
        val reading = decision.display
        return when {
            note == null -> Readout()
            reading != null -> Readout(note, Cents.shown(reading.cents, reading.hz), Readout.shownHz(reading.hz))
            // The readout holds its last value while the note is held and this hop added nothing.
            else -> copy(note = note)
        }
    }

    companion object {
        private const val UNKNOWN_DEVICE = "Microphone"

        /** About one second of hops: 22 at the 46.44 ms hop. */
        const val DIAGNOSTICS_HOPS = 22
    }
}
