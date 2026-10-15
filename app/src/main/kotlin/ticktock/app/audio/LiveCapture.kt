// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.audio

import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import ticktock.core.contracts.AudioInput
import ticktock.core.contracts.HistorySink
import ticktock.core.contracts.InputException
import ticktock.core.contracts.InputFailure
import ticktock.core.contracts.InputInfo
import ticktock.core.detect.AnalysisCounters
import ticktock.core.detect.Decision
import ticktock.core.detect.Detector
import ticktock.core.detect.HopAnalyser
import ticktock.core.detect.Release
import ticktock.core.detect.ResponseMode
import ticktock.core.detect.YinEstimator
import ticktock.core.music.Cents
import ticktock.core.music.Pitch
import ticktock.engine.input.AnalysisThread
import ticktock.engine.input.InputListener
import ticktock.engine.input.RecordingState
import java.util.concurrent.atomic.AtomicReference
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
    /** The sounding MIDI note behind [note]; the screen spells it written or concert. */
    val midi: Int? = null,
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

/**
 * The last [TRACE_HOPS] hops of accepted cents, oldest first, NaN where
 * a hop had no reading, and the attacks that began inside that window.
 */
class TraceSnapshot(val cents: FloatArray, val attacks: Int) {
    companion object {
        /** About five seconds of hops (TRC-01). */
        val TRACE_HOPS: Int = ticktock.core.detect.Hop.count(5.0)
        val EMPTY = TraceSnapshot(FloatArray(TRACE_HOPS) { Float.NaN }, 0)
    }
}

/**
 * A note that has ended, from its attack to its release (EAR-02): its held
 * mean, the attack's settled reading, how long it sounded and its own trace.
 * [serial] counts notes, so a screen can tell one ended note from the next.
 */
class LastNote(
    val midi: Int,
    val meanCents: Double,
    val meanHz: Double,
    val settledCents: Double?,
    val seconds: Double,
    val points: FloatArray,
    val serial: Int,
)

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
    /** Where saved readings go; it queues and returns, so the analysis thread never waits on disk (SES-06). */
    private val history: HistorySink? = null,
) : InputListener {
    private val detector = Detector(mode).also { it.setA4(a4Hz) }
    val a4Hz: Double get() = detector.a4.hz
    private val thread = AnalysisThread(
        HopAnalyser(detector, YinEstimator(detector)), promote,
        beforeBatch = ::applyRequests, onDecisions = ::onDecisions,
    )
    private val stateFlow = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = stateFlow
    private val levelFlow = MutableStateFlow(Level())
    val level: StateFlow<Level> = levelFlow
    private val diagnosticsFlow = MutableStateFlow(CaptureDiagnostics())
    val diagnostics: StateFlow<CaptureDiagnostics> = diagnosticsFlow
    private val traceFlow = MutableStateFlow(TraceSnapshot.EMPTY)

    /** The trace, moving every hop; only the trace plot reads it (POL-31). */
    val trace: StateFlow<TraceSnapshot> = traceFlow

    private val lastNoteFlow = MutableStateFlow<LastNote?>(null)

    /** The most recent note that ended; moves once per note. */
    val lastNote: StateFlow<LastNote?> = lastNoteFlow

    // The analysis thread's alone: the note being held, from its attack.
    private var noteMidi: Int? = null
    private var noteStartHop = 0L
    private var noteLastReadingHop = 0L
    private var noteCentsSum = 0.0
    private var noteHzSum = 0.0
    private var noteReadings = 0
    private var noteSettled: Double? = null
    private val notePoints = FloatArray(NOTE_POINTS_MAX)
    private var notePointCount = 0
    private var noteSerial = 0

    // The analysis thread's alone: the trace's ring and the hops at which attacks began.
    private val traceRing = FloatArray(TraceSnapshot.TRACE_HOPS) { Float.NaN }
    private var traceHead = 0
    private var traceHop = -1L
    private val attackHops = ArrayDeque<Long>()

    // Settings changed on the UI thread wait here for the analysis thread, which
    // alone touches the detector, and take effect from the next batch of hops.
    private val requestedA4 = AtomicReference<Double?>(null)

    private val requestedMode = AtomicReference<ResponseMode?>(null)

    /** Measures against [hz] from the next hop on; a change starts a new A4 generation (POL-19). */
    fun requestA4(hz: Double) {
        requestedA4.set(hz)
    }

    /** Switches the response mode from the next hop on (POL-05). */
    fun requestMode(mode: ResponseMode) {
        requestedMode.set(mode)
    }

    /** Hops since the diagnostics were last sampled; the analysis thread's alone. */
    private var hopsSinceDiagnostics = 0

    fun start(input: AudioInput) {
        val result = thread.start(input)
        hopsSinceDiagnostics = 0
        levelFlow.value = Level()
        diagnosticsFlow.value = CaptureDiagnostics()
        stateFlow.update { previous ->
            result.fold(
                onSuccess = { info -> CaptureState(info = info, absentDevice = previous.absentDevice) },
                onFailure = { error ->
                    val failure = (error as? InputException)?.failure
                        ?: InputFailure.Refused(UNKNOWN_DEVICE, emptyList())
                    CaptureState(failure = failure)
                },
            )
        }
    }

    fun stop() {
        thread.stop()
        stateFlow.update { CaptureState() }
        levelFlow.value = Level()
        diagnosticsFlow.value = CaptureDiagnostics()
        // The analysis thread has stopped, so its trace state is safe to clear here.
        traceRing.fill(Float.NaN)
        traceHead = 0
        traceHop = -1L
        attackHops.clear()
        traceFlow.value = TraceSnapshot.EMPTY
        noteMidi = null
        lastNoteFlow.value = null
    }

    /** The platform's recording report, from the source's flow; the screen forwards it here. */
    fun recordingReported(recording: RecordingState?) {
        stateFlow.update { it.copy(recording = recording) }
    }

    override fun onStopped(failure: InputFailure) {
        stateFlow.update { it.copy(info = null, failure = failure, recording = null) }
    }

    override fun onResumed(info: InputInfo) {
        stateFlow.update { it.copy(info = info, failure = null) }
    }

    override fun onDeviceAbsent(failure: InputFailure.Disconnected) {
        stateFlow.update { it.copy(absentDevice = failure) }
    }

    private fun applyRequests() {
        requestedA4.getAndSet(null)?.let { hz ->
            if (hz != detector.a4.hz) detector.setA4(hz)?.let(::endNote)
        }
        requestedMode.getAndSet(null)?.let { mode ->
            if (mode != detector.mode) detector.setMode(mode)?.let(::endNote)
        }
    }

    private fun onDecisions(decisions: List<Decision>) {
        if (history != null) {
            for (decision in decisions) decision.saved?.let { history.accept(it, decision.firstSettled) }
        }
        val newest = decisions.last()
        levelFlow.value = Level(newest.hop, Level.db(newest.level))
        stateFlow.update { previous ->
            val readout = previous.readout.after(newest)
            // A held readout reuses the state; retries preserve concurrent stream reports.
            if (readout == previous.readout) previous else previous.copy(readout = readout)
        }
        recordTrace(decisions)
        recordNotes(decisions)
        hopsSinceDiagnostics += decisions.size
        if (hopsSinceDiagnostics >= DIAGNOSTICS_HOPS) {
            hopsSinceDiagnostics = 0
            diagnosticsFlow.value = CaptureDiagnostics(thread.counters, thread.lastHopNanos)
        }
    }

    private fun recordNotes(decisions: List<Decision>) {
        for (decision in decisions) {
            // A note change releases the old note and attacks the new one on the same hop.
            decision.release?.let(::endNote)
            decision.attack?.let { attack ->
                noteMidi = attack.midiSounding
                noteStartHop = attack.hop
                noteCentsSum = 0.0
                noteHzSum = 0.0
                noteReadings = 0
                noteSettled = null
                notePointCount = 0
            }
            val held = noteMidi
            val reading = decision.display
            if (held != null && reading != null && reading.midiSounding == held) {
                noteLastReadingHop = reading.hop
                noteCentsSum += reading.cents
                noteHzSum += reading.hz
                noteReadings++
                if (notePointCount < notePoints.size) notePoints[notePointCount++] = reading.cents.toFloat()
            }
            if (decision.firstSettled) noteSettled = decision.saved?.cents
        }
    }

    /** Completes both hop-stream releases and settings releases on the analysis thread. */
    private fun endNote(release: Release) {
        if (noteMidi != null && release.midiSounding == noteMidi) {
            if (noteReadings > 0) {
                lastNoteFlow.value = LastNote(
                    midi = release.midiSounding,
                    meanCents = noteCentsSum / noteReadings,
                    meanHz = noteHzSum / noteReadings,
                    settledCents = noteSettled,
                    seconds = (noteLastReadingHop - noteStartHop + 1) * ticktock.core.detect.Hop.SECONDS,
                    points = notePoints.copyOf(notePointCount),
                    serial = ++noteSerial,
                )
            }
            noteMidi = null
        }
    }

    private fun recordTrace(decisions: List<Decision>) {
        for (decision in decisions) {
            // Missing decisions still occupy their elapsed hop time in the fixed window.
            val missing = (decision.hop - traceHop - 1).coerceIn(0L, traceRing.size.toLong()).toInt()
            repeat(missing) { appendTrace(Float.NaN) }
            if (decision.attack != null) attackHops.addLast(decision.hop)
            appendTrace(decision.display?.cents?.toFloat() ?: Float.NaN)
            traceHop = decision.hop
        }
        val newest = decisions.last().hop
        while (attackHops.isNotEmpty() && attackHops.first() <= newest - traceRing.size) attackHops.removeFirst()
        val ordered = FloatArray(traceRing.size) { traceRing[(traceHead + it) % traceRing.size] }
        traceFlow.value = TraceSnapshot(ordered, attackHops.size)
    }

    private fun appendTrace(cents: Float) {
        traceRing[traceHead] = cents
        traceHead = (traceHead + 1) % traceRing.size
    }

    private fun Readout.after(decision: Decision): Readout {
        val note = decision.incumbent?.let(Pitch::label)
        val reading = decision.display
        return when {
            note == null -> Readout()
            reading != null -> Readout(note, decision.incumbent, Cents.shown(reading.cents, reading.hz), Readout.shownHz(reading.hz))
            // The readout holds its last value while the note is held and this hop added nothing.
            else -> copy(note = note, midi = decision.incumbent)
        }
    }

    companion object {
        private const val UNKNOWN_DEVICE = "Microphone"

        /** The longest note whose trace is kept for Last note: about 20 s of hops. */
        const val NOTE_POINTS_MAX = 430

        /** About one second of hops: 22 at the 46.44 ms hop. */
        const val DIAGNOSTICS_HOPS = 22
    }
}
