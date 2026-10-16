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
import ticktock.core.contracts.SoundingNotes
import ticktock.core.detect.AnalysisCounters
import ticktock.core.detect.Decision
import ticktock.core.detect.Detector
import ticktock.core.detect.HopAnalyser
import ticktock.core.detect.NoSoundingNotes
import ticktock.core.detect.Release
import ticktock.core.detect.ResponseMode
import ticktock.core.detect.StrayCounter
import ticktock.core.detect.YinEstimator
import ticktock.core.music.Cents
import ticktock.core.music.Pitch
import ticktock.core.music.Transposition
import ticktock.data.prefs.PrefsPublication
import ticktock.engine.input.AnalysisThread
import ticktock.engine.input.InputListener
import ticktock.engine.input.RecordingState
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
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
    sounding: SoundingNotes = NoSoundingNotes,
    private val onDuck: (Float, () -> Unit) -> Unit = { _, settled -> settled() },
    noiseFloorDb: Int? = null,
    instrumentKey: String = "",
    instrumentRevision: Long = 0,
    initialPublication: PrefsPublication? = null,
) : InputListener {
    private val detector = Detector(mode, sounding = sounding).also { it.setA4(a4Hz); it.setNoiseFloor(noiseFloorDb) }
    private val duckLock = Any()
    private var duckEnabled = false
    val a4Hz: Double get() = detector.a4.hz
    private val analyser = HopAnalyser(detector, YinEstimator(detector))
    private val readyDecisions = ArrayList<Decision>()
    private val thread = AnalysisThread(
        analyser, promote, beforeBatch = { readyDecisions.clear(); applyRequests() },
        onDecisions = {
            if (!resetAfterInterruption.get() && instrumentIsCurrent() && readyDecisions.isNotEmpty()) onDecisions(readyDecisions)
        },
        onDecision = { decision ->
            // A route or voice change can arrive while an old window is being estimated.
            if (resetAfterInterruption.get() || !instrumentIsCurrent()) {
                readyDecisions.clear()
                applyRequests()
            } else {
                dispatchDuck(decision)
                readyDecisions += decision
            }
        },
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

    private val rangeHintFlow = RangeHintFlow()

    /** Only a changed range judgment is published; accepted-reading ages stay on analysis. */
    val rangeHint: StateFlow<RangeHint?> = rangeHintFlow

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

    private val requestedNoiseFloor = AtomicReference<Int?>(null)
    private val requestedMode = AtomicReference<ResponseMode?>(null)
    private val resetAfterInterruption = AtomicBoolean(false)
    private data class InstrumentEpoch(val serial: Long, val key: String)
    private data class InstrumentRequest(
        val epoch: InstrumentEpoch,
        val profile: RangeHintProfile?,
        val hintGeneration: Long,
        val publicationRevision: Long,
        val rangeRevision: Long,
    )
    private val instrumentRequestLock = Any()
    private val requestedInstrument = AtomicReference(InstrumentRequest(
        epoch = initialPublication?.let { InstrumentEpoch(it.instrumentRevision, it.prefs.instrumentKey) }
            ?: InstrumentEpoch(instrumentRevision, instrumentKey),
        profile = initialPublication?.let { RangeHintProfile.from(it.prefs) },
        hintGeneration = 0,
        publicationRevision = initialPublication?.revision ?: -1,
        rangeRevision = initialPublication?.rangeRevision ?: 0,
    ))
    @Volatile private var activeInstrument = requestedInstrument.get().epoch
    // Only analysis mutates the counter and its profile generation after construction.
    private var activeHintRequest = requestedInstrument.get()
    private var strayCounter = activeHintRequest.profile?.let { StrayCounter(it.instrument, it.range) }

    /** Publish the epoch and ring boundary together; only analysis mutates detector or statistics. */
    fun requestInstrument(key: String, revision: Long) {
        synchronized(instrumentRequestLock) {
            val previous = requestedInstrument.get()
            if (revision <= previous.epoch.serial) return
            requestedInstrument.set(previous.copy(epoch = InstrumentEpoch(revision, key), profile = null,
                hintGeneration = previous.hintGeneration + 1))
            analyser.discardPendingInput()
        }
        invalidateRangeHint()
    }

    /** Submit the instrument epoch and its imported range together, never in separate requests. */
    fun requestInstrument(publication: PrefsPublication) {
        val profile = RangeHintProfile.from(publication.prefs)
        synchronized(instrumentRequestLock) {
            val previous = requestedInstrument.get()
            if (publication.revision <= previous.publicationRevision || publication.instrumentRevision < previous.epoch.serial ||
                publication.rangeRevision < previous.rangeRevision) return
            val epoch = InstrumentEpoch(publication.instrumentRevision, publication.prefs.instrumentKey)
            val changed = epoch != previous.epoch || profile != previous.profile || publication.rangeRevision != previous.rangeRevision
            requestedInstrument.set(InstrumentRequest(epoch, profile,
                previous.hintGeneration + if (changed) 1 else 0, publication.revision, publication.rangeRevision))
            if (epoch != previous.epoch) analyser.discardPendingInput()
        }
    }

    /** Called after releasing settings/request locks, because a flow publication may resume its collector. */
    fun invalidateRangeHint() {
        rangeHintFlow.invalidate(requestedInstrument.get().hintGeneration)
    }

    private fun instrumentIsCurrent(): Boolean = activeInstrument == requestedInstrument.get().epoch

    /** Also guards a prefs value published just before its synchronous listener reaches capture. */
    fun acceptsInstrument(key: String, revision: Long): Boolean {
        val active = activeInstrument
        return active.key == key && active.serial == revision && active == requestedInstrument.get().epoch
    }

    /** Measures against [hz] from the next hop on; a change starts a new A4 generation (POL-19). */
    fun requestA4(hz: Double) {
        requestedA4.set(hz)
    }

    /** Switches the response mode from the next hop on (POL-05). */
    fun requestMode(mode: ResponseMode) {
        requestedMode.set(mode)
    }

    /** Noise-floor changes are consumed by the analysis owner before the next batch. */
    fun requestNoiseFloor(db: Int) { requestedNoiseFloor.set(db) }

    /** Hops since the diagnostics were last sampled; the analysis thread's alone. */
    private var hopsSinceDiagnostics = 0

    fun start(input: AudioInput) {
        thread.stop()
        resetAfterInterruption.set(true)
        synchronized(duckLock) { duckEnabled = true }
        hopsSinceDiagnostics = 0
        levelFlow.value = Level()
        diagnosticsFlow.value = CaptureDiagnostics()
        stateFlow.update { CaptureState(absentDevice = it.absentDevice) }
        val result = thread.start(input)
        stateFlow.update { previous ->
            result.fold(
                // Capture callbacks may report a stop or a newer route before open returns.
                onSuccess = { info ->
                    if (previous.failure != null || previous.info != null) previous else previous.copy(info = info)
                },
                onFailure = { error ->
                    val failure = (error as? InputException)?.failure
                        ?: InputFailure.Refused(UNKNOWN_DEVICE, emptyList())
                    previous.copy(info = null, failure = failure)
                },
            )
        }
        if (!stateFlow.value.isListening) stopDuck()
    }

    fun stop() {
        thread.stop()
        stopDuck()
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
        resetAfterInterruption.set(true)
        stopDuck()
    }

    override fun onResumed(info: InputInfo) {
        analyser.inputBufferChanged(info.bufferFrames)
        synchronized(duckLock) {
            duckEnabled = true
            outputContextChanged()
        }
        stateFlow.update { it.copy(info = info, failure = null) }
    }

    override fun onDeviceAbsent(failure: InputFailure.Disconnected) {
        stateFlow.update { it.copy(absentDevice = failure) }
    }

    /** Callable from output control without waiting on the analysis thread or its command lock. */
    fun outputContextChanged() {
        analyser.deferBleedEvidence()
        resetAfterInterruption.set(true)
    }

    private fun applyRequests() {
        // Taking the snapshot under the request lock guarantees its ring fence is already set.
        // Release that lock before calling output control: prefs can originate from output too.
        val request = synchronized(instrumentRequestLock) { requestedInstrument.get() }
        val instrument = request.epoch
        if (instrument != activeInstrument) {
            detector.reset() // An instrument change is not a completed-note reveal.
            clearInstrumentStatistics()
            stateFlow.update { it.copy(readout = Readout()) }
            synchronized(duckLock) {
                if (duckEnabled) onDuck(1f, analyser.deferBleedEvidence())
            }
            activeInstrument = instrument
        }
        applyHintProfile(request)
        if (resetAfterInterruption.getAndSet(false)) {
            detector.reset()?.let(::endNote)
            stateFlow.update { it.copy(readout = Readout()) }
            synchronized(duckLock) {
                if (duckEnabled) onDuck(1f, analyser.deferBleedEvidence())
            }
        }
        requestedA4.getAndSet(null)?.let { hz ->
            if (hz != detector.a4.hz) detector.setA4(hz)?.let(::endNote)
        }
        requestedNoiseFloor.getAndSet(null)?.let(detector::setNoiseFloor)
        requestedMode.getAndSet(null)?.let { mode ->
            if (mode != detector.mode) detector.setMode(mode)?.let(::endNote)
        }
    }

    private fun clearInstrumentStatistics() {
        traceRing.fill(Float.NaN)
        traceHead = 0
        traceHop = -1L
        attackHops.clear()
        traceFlow.value = TraceSnapshot.EMPTY
        noteMidi = null
        noteStartHop = 0L
        noteLastReadingHop = 0L
        noteCentsSum = 0.0
        noteHzSum = 0.0
        noteReadings = 0
        noteSettled = null
        notePoints.fill(Float.NaN)
        notePointCount = 0
        lastNoteFlow.value = null
    }

    private fun dispatchDuck(decision: Decision) {
        synchronized(duckLock) {
            if (duckEnabled) decision.duck?.let { onDuck(it, analyser.deferBleedEvidence()) }
        }
    }

    private fun onDecisions(decisions: List<Decision>) {
        for (decision in decisions) {
            val request = requestedInstrument.get()
            if (activeInstrument != request.epoch) return
            // A range-only import changes judgment, not detection or history admission.
            applyHintProfile(request)
            decision.saved?.let { reading ->
                strayCounter?.observe(reading)
                history?.accept(reading, decision.firstSettled)
            }
        }
        if (!instrumentIsCurrent()) return
        publishRangeHint()
        val newest = decisions.last()
        levelFlow.value = Level(newest.hop, Level.db(newest.level))
        stateFlow.update { previous ->
            val readout = decisions.fold(previous.readout) { held, decision -> held.after(decision) }
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

    private fun applyHintProfile(request: InstrumentRequest) {
        if (request.hintGeneration == activeHintRequest.hintGeneration) return
        strayCounter = request.profile?.let { StrayCounter(it.instrument, it.range) }
        activeHintRequest = request
        rangeHintFlow.invalidate(request.hintGeneration)
    }

    private fun publishRangeHint() {
        val request = activeHintRequest
        if (request.hintGeneration != requestedInstrument.get().hintGeneration) return
        val profile = request.profile
        val counter = strayCounter
        val hint = if (profile == null || counter == null || !counter.hinting) null else {
            val written = counter.strays.map { Transposition.writtenFromSounding(it, profile.instrument.transposition) }
            val direction = when {
                written.all { it < profile.range.extendedLow.midi } -> RangeHintDirection.BELOW
                written.all { it > profile.range.extendedHigh.midi } -> RangeHintDirection.ABOVE
                else -> RangeHintDirection.OUTSIDE
            }
            RangeHint(request.epoch.key, request.epoch.serial, profile.range, direction, request.rangeRevision)
        }
        rangeHintFlow.publish(request.hintGeneration, hint)
    }

    /** A disconnect waits for an in-flight command before restoring gain. Later batches cannot duck a stopped input. */
    private fun stopDuck() {
        synchronized(duckLock) {
            duckEnabled = false
            analyser.deferBleedEvidence()
            onDuck(1f) {}
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
                // Each position is elapsed hop time; rejected and missing hops remain gaps.
                val position = (reading.hop - noteStartHop).coerceIn(0L, notePoints.size.toLong()).toInt()
                while (notePointCount < position) notePoints[notePointCount++] = Float.NaN
                if (position < notePoints.size) {
                    notePoints[position] = reading.cents.toFloat()
                    notePointCount = position + 1
                }
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
            midi == decision.incumbent -> this
            else -> Readout(note = note, midi = decision.incumbent)
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
