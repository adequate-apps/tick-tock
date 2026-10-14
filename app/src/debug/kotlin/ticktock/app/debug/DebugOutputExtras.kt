// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import ticktock.app.R
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputInfo
import ticktock.core.contracts.OutputPort
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason
import ticktock.core.contracts.Voice
import ticktock.core.music.Pitch
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.Caption
import ticktock.design.components.Field
import ticktock.design.components.GlText
import ticktock.design.components.Pane
import ticktock.design.components.SettingRow
import ticktock.engine.output.NativeDiagnostics
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** What the port has reported since the pane started, folded from its events. */
private data class OutputState(
    /** The variant name of the newest event, for the row and the harness. */
    val lastEvent: String? = null,
    val beats: Long = 0,
    val lastBeat: OutputEvent.Beat? = null,
    val counters: OutputEvent.Counters? = null,
    /** The last park, cleared by the next open. */
    val parked: ParkReason? = null,
    /** The last refusal, cleared by the next open. */
    val failed: OutputEvent.Failed? = null,
) {
    fun after(event: OutputEvent): OutputState = when (event) {
        is OutputEvent.Opened, is OutputEvent.Resumed -> copy(parked = null, failed = null)
        is OutputEvent.Beat -> copy(beats = beats + 1, lastBeat = event)
        is OutputEvent.Counters -> copy(counters = event)
        is OutputEvent.Parked -> copy(parked = event.reason)
        is OutputEvent.Failed -> copy(failed = event)
        OutputEvent.Closed -> this
    }.copy(lastEvent = event.name())
}

/**
 * The debug-only output pane under the Tune pane: the drone and the
 * metronome the launch extras asked for, started through the one door to
 * the engine, with the stream, the beats and the counters it reports, one
 * Gridline row each. It exists so M2's output path can be seen working
 * before M5 builds the real panes, and M5 removes it. It appears only when
 * [OutputSeam] has something to drive for this launch.
 */
@Composable
fun DebugOutputExtras() {
    val activity = LocalActivity.current ?: return
    val choice = remember { OutputSeam.choose(activity) } ?: return
    val port = choice.port
    var state by remember { mutableStateOf(OutputState()) }
    val info by port.info.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val collecting = remember { AtomicBoolean(false) }
    LifecycleResumeEffect(choice) {
        if (collecting.getAndSet(true)) {
            start(port, choice)
        } else {
            // The real port replays nothing, so the first start waits for the
            // subscription; otherwise Opened is emitted before anyone listens.
            scope.launch {
                port.events.onSubscribed { start(port, choice) }.collect { event ->
                    state = state.after(event)
                    // One line per event for tools/device/output.sh; beats are at most a few per second.
                    Log.i(LOG_TAG, "output event=${event.line()}")
                }
            }
        }
        onPauseOrDispose {
            // Leaving the screen stops exactly what this pane started, tool by tool.
            choice.metroBpm?.let {
                send(port, OutputCommand.MetroStop)
                send(port, OutputCommand.Unregister(OutputTool.METRONOME))
            }
            choice.droneMidi?.let {
                send(port, OutputCommand.VoiceOff(OutputSeam.DRONE_SLOT))
                send(port, OutputCommand.Unregister(OutputTool.DRONE))
            }
        }
    }

    var native by remember { mutableStateOf<NativeDiagnostics?>(null) }
    LaunchedEffect(info) {
        // The render callback's own clock, read once a second while a stream is open.
        while (info != null) {
            native = choice.nativeDiagnostics()
            native?.let { Log.i(LOG_TAG, "output diagnostics ${it.line()}") }
            delay(DIAGNOSTICS_PERIOD_MILLIS)
        }
        native = null
    }

    val sentence = sentence(info, state)
    val route = info?.let { "${it.deviceName}, ${it.routeClass}, ${it.framesPerBlock} frames per block, ${it.latencyMillis} ms" }
        ?: stringResource(R.string.debug_output_no_stream)
    val drone = choice.droneMidi?.let { midi ->
        val a4 = choice.a4Hz?.let { String.format(Locale.US, " at A4 = %.0f Hz", it) } ?: ""
        stringResource(R.string.debug_drone_value, Pitch.label(midi), midi, a4, OutputSeam.DRONE_GAIN.toString())
    } ?: stringResource(R.string.debug_output_off)
    val metro = choice.metroBpm?.let { stringResource(R.string.debug_metro_value, it) } ?: stringResource(R.string.debug_output_off)
    val beats = state.lastBeat?.let { stringResource(R.string.debug_beats_value, state.beats, it.index, it.beatInBar, it.frame) }
        ?: stringResource(R.string.debug_beats_none)
    val counters = state.counters?.let { stringResource(R.string.debug_counters_value, it.lateBeatsDropped, it.underruns) }
        ?: stringResource(R.string.debug_counters_none)
    val lastEvent = state.lastEvent ?: stringResource(R.string.debug_event_none)
    val nativeLine = native?.let { stringResource(R.string.debug_native_value, it.framesRendered, it.underruns, it.bufferBursts, it.lateBeatsDropped) }
        ?: stringResource(R.string.debug_native_none)

    Pane(
        title = stringResource(R.string.debug_output_title),
        state = sentence,
        modifier = Modifier.fillMaxWidth(),
        tag = "debug-output",
    ) {
        Field(Modifier.fillMaxWidth()) {
            // Sentences rather than values: stacked under a caption so they wrap as prose.
            CaptionedLine(caption = stringResource(R.string.debug_row_route), text = route, tag = "debug-route")
            SettingRow(label = stringResource(R.string.debug_row_last_event), value = lastEvent, tag = "debug-last-event")
            SettingRow(label = stringResource(R.string.debug_row_drone), value = drone, tag = "debug-drone")
            SettingRow(label = stringResource(R.string.debug_row_metro), value = metro, tag = "debug-metro")
            CaptionedLine(caption = stringResource(R.string.debug_row_beats), text = beats, tag = "debug-beats")
            SettingRow(label = stringResource(R.string.debug_row_output_counters), value = counters, tag = "debug-counters")
            CaptionedLine(caption = stringResource(R.string.debug_row_native), text = nativeLine, tag = "debug-native")
        }
    }
}

@Composable
private fun CaptionedLine(caption: String, text: String, tag: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp2).testTag(tag)) {
        Caption(text = caption)
        GlText(text = text, style = GridlineType.value, color = GridlineColors.textSecondary, modifier = Modifier.testTag("$tag-value"))
    }
}

private fun start(port: OutputPort, choice: OutputSeam.Choice) {
    choice.droneMidi?.let { midi ->
        send(port, OutputCommand.Register(OutputTool.DRONE))
        // Retune first, so the voice strikes in tune rather than sliding there (TON-03 covers the slide).
        choice.a4Hz?.let { send(port, OutputCommand.SetA4(it)) }
        send(port, OutputCommand.VoiceOn(OutputSeam.DRONE_SLOT, midi, Voice.SINE, OutputSeam.DRONE_GAIN))
    }
    choice.metroBpm?.let { bpm ->
        send(port, OutputCommand.Register(OutputTool.METRONOME))
        send(port, OutputCommand.MetroStart(OutputSeam.metroSetup(bpm)))
    }
}

/** Runs [action] once the collector is registered: on a shared flow exactly then, on any other flow just before collecting. */
private fun <T> Flow<T>.onSubscribed(action: () -> Unit): Flow<T> =
    if (this is SharedFlow<T>) onSubscription { action() } else onStart { action() }

private fun send(port: OutputPort, cmd: OutputCommand) {
    Log.i(LOG_TAG, "output sent=$cmd")
    port.send(cmd)
}

/** The pane's own fact: what the output stream is doing, in one sentence. */
@Composable
private fun sentence(info: OutputInfo?, state: OutputState): String {
    val failed = state.failed
    return when {
        info != null -> stringResource(R.string.debug_output_open, info.deviceName, info.sampleRate)
        failed != null -> stringResource(R.string.debug_output_failed, failed.deviceName, failed.refusedRates.joinToString())
        state.parked == ParkReason.HEADPHONES_DISCONNECTED -> stringResource(R.string.debug_output_paused_headphones)
        state.parked == ParkReason.INCOMING_CALL -> stringResource(R.string.debug_output_paused_call)
        state.parked == ParkReason.OUTPUT_FAILED -> stringResource(R.string.debug_output_paused_failed)
        state.parked == ParkReason.BACKGROUND -> stringResource(R.string.debug_output_paused_background)
        else -> stringResource(R.string.debug_output_closed)
    }
}

private fun OutputEvent.name(): String = line().substringBefore(' ')

private fun OutputEvent.line(): String = when (this) {
    is OutputEvent.Opened -> "Opened ${info.line()}"
    is OutputEvent.Resumed -> "Resumed tools=$tools ${info.line()}"
    OutputEvent.Closed -> "Closed"
    is OutputEvent.Beat -> "Beat index=$index beatInBar=$beatInBar frame=$frame nanos=$nanos"
    is OutputEvent.Parked -> "Parked reason=$reason tools=$tools"
    is OutputEvent.Failed -> "Failed device=\"$deviceName\" rates=${refusedRates.joinToString(",")}"
    is OutputEvent.Counters -> "Counters late=$lateBeatsDropped underruns=$underruns"
}

private fun OutputInfo.line(): String =
    "rate=$sampleRate burst=$framesPerBlock device=\"$deviceName\" class=$routeClass latency=$latencyMillis"

private fun NativeDiagnostics.line(): String = String.format(
    Locale.US,
    "frames=%d rate=%d underruns=%d bursts=%d growth=%d late=%d droppedCommands=%d droppedBeats=%d open=%b parked=%b",
    framesRendered, sampleRate, underruns, bufferBursts, growthSteps, lateBeatsDropped, droppedCommands, droppedBeats, streamOpen, parked,
)

private const val LOG_TAG = "ticktock.debug"
private const val DIAGNOSTICS_PERIOD_MILLIS = 1_000L
