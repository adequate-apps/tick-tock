// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ticktock.app.audio.CaptureState
import ticktock.app.audio.Level
import ticktock.app.audio.LiveCapture
import ticktock.app.audio.TraceSnapshot
import ticktock.core.contracts.AudioInput
import ticktock.core.contracts.HistorySink
import ticktock.core.contracts.SoundingNotes
import ticktock.core.detect.NoSoundingNotes
import ticktock.core.prefs.Prefs
import ticktock.engine.input.AndroidRecorderPlatform
import ticktock.engine.input.AudioRecordSource
import ticktock.engine.input.InputListener

/**
 * The microphone for the whole app. While the app is in the foreground and
 * the player has allowed the microphone, the session listens, whichever tab
 * is open, and its readings drive the Tune pane and reach history (unless
 * Capture is paused, which the run context carries). In the background it
 * stops (OUT-11, decision 12). A4 and the response mode follow prefs from
 * the next hop on.
 *
 * [makeInput] builds the input for one listening stretch; the debug build
 * can swap the microphone for a synthesized tone.
 */
class TuneSession(
    private val context: Context,
    prefs: Prefs,
    history: HistorySink,
    var makeInput: (InputListener) -> AudioInput = { listener ->
        AudioRecordSource(AndroidRecorderPlatform(context.applicationContext), listener = listener)
    },
    /** True when the microphone permission is held; a test supplies its own answer. */
    var micAllowed: () -> Boolean = {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    },
    sounding: SoundingNotes = NoSoundingNotes,
    onDuck: (Float, () -> Unit) -> Unit = { _, settled -> settled() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val capture = LiveCapture(mode = prefs.responseMode, a4Hz = prefs.a4Hz, history = history,
        sounding = sounding, onDuck = onDuck, noiseFloorDb = prefs.noiseFloorDb)
    private var requestedA4 = prefs.a4Hz
    private var requestedMode = prefs.responseMode
    private var requestedNoiseFloor = prefs.noiseFloorDb
    private var input: AudioInput? = null
    private var reporting: Job? = null

    val state: StateFlow<CaptureState> = capture.state
    val level: StateFlow<Level> = capture.level
    val trace: StateFlow<TraceSnapshot> = capture.trace
    val lastNote: StateFlow<ticktock.app.audio.LastNote?> = capture.lastNote

    private val wantedFlow = MutableStateFlow(false)

    /** True while the app wants to listen: in the foreground with the microphone allowed. */
    val wanted: StateFlow<Boolean> = wantedFlow

    /** Called when the app comes to the foreground, and again once the player allows the microphone. */
    fun resume() {
        if (!micAllowed() || input != null) return
        val fresh = makeInput(capture)
        input = fresh
        wantedFlow.value = true
        capture.start(fresh)
        (fresh as? AudioRecordSource)?.let { source ->
            reporting = scope.launch { source.recordingState.collect(capture::recordingReported) }
        }
    }

    /** Called when the app goes to the background: everything stops, and nothing resumes by itself until the app returns. */
    fun pause() {
        reporting?.cancel()
        reporting = null
        if (input != null) capture.stop()
        input = null
        wantedFlow.value = false
    }

    /** Stops and starts again, for "Try again" after the microphone was busy or refused. */
    fun retry() {
        pause()
        resume()
    }

    fun settingsChanged(prefs: Prefs) {
        if (prefs.noiseFloorDb != requestedNoiseFloor) {
            requestedNoiseFloor = prefs.noiseFloorDb
            capture.requestNoiseFloor(prefs.noiseFloorDb)
        }
        if (prefs.a4Hz != requestedA4) {
            requestedA4 = prefs.a4Hz
            capture.requestA4(prefs.a4Hz)
        }
        if (prefs.responseMode != requestedMode) {
            requestedMode = prefs.responseMode
            capture.requestMode(prefs.responseMode)
        }
    }

    fun outputContextChanged() = capture.outputContextChanged()

    /** Tests only. */
    fun close() {
        pause()
        scope.cancel()
    }
}
