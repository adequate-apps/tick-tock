// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.R
import ticktock.app.audio.CaptureState
import ticktock.app.audio.LiveCapture
import ticktock.core.contracts.InputFailure
import ticktock.core.music.Cents
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.Caption
import ticktock.design.components.Field
import ticktock.design.components.GlText
import ticktock.design.components.Pane
import ticktock.design.components.SettingRow
import java.util.Locale

/**
 * The debug-only live readout under the Tune pane: note, whole cents, Hz,
 * level, the input in use and the analysis counters, one Gridline row each.
 * It exists so M2's capture path can be seen working before M4 builds the
 * real pane, and M4 removes it. It appears only when [InputSeam] has an input
 * for this launch.
 */
@Composable
fun DebugTuneExtras() {
    val activity = LocalActivity.current ?: return
    val choice = remember { InputSeam.choose(activity) } ?: return
    val capture = remember { LiveCapture() }
    val input = remember { choice.make(capture) }
    var granted by remember {
        mutableStateOf(!choice.needsMicrophone || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.RECORD_AUDIO) }

    LifecycleResumeEffect(granted) {
        if (granted) capture.start(input)
        onPauseOrDispose { capture.stop() }
    }

    val state by capture.state.collectAsStateWithLifecycle()
    InputSeam.recordingStateOf(input)?.let { flow ->
        val recording by flow.collectAsStateWithLifecycle()
        LaunchedEffect(recording) { capture.recordingReported(recording) }
    }

    val sentence = sentence(state, granted, refused)
    val readout = state.readout
    val note = readout.note ?: Cents.NO_READING
    val cents = readout.cents?.let { "${Cents.format(it)} c" } ?: Cents.NO_READING
    val hz = readout.hz?.let { String.format(Locale.US, "%.1f Hz", it) } ?: Cents.NO_READING
    val level = String.format(Locale.US, "%.0f dBFS", readout.levelDb)
    val source = state.info?.let { "${it.source} at ${it.sampleRate} Hz, ${it.deviceName}" } ?: stringResource(R.string.debug_no_input)
    val platform = state.recording?.let { report ->
        val effects = if (report.effects.isEmpty()) stringResource(R.string.debug_no_effects) else report.effects.joinToString()
        if (report.reported) "${report.source}, $effects" else stringResource(R.string.debug_not_reported)
    } ?: Cents.NO_READING
    val counters = String.format(
        Locale.US,
        "%d hops, %d dropped, %d errors, %.1f ms per hop",
        state.counters.hopsAnalysed,
        state.counters.droppedHops,
        state.counters.analysisErrors,
        state.lastHopNanos / NANOS_PER_MILLI,
    )
    LaunchedEffect(note, cents, hz, source) {
        // One line per change for tools/device/readout.sh, never one per hop.
        Log.i(LOG_TAG, "readout note=$note cents=$cents hz=$hz level=$level source=$source")
    }

    Pane(
        title = stringResource(R.string.debug_readout_title),
        state = sentence,
        modifier = Modifier.fillMaxWidth(),
        tag = "debug-readout",
    ) {
        Field(Modifier.fillMaxWidth()) {
            SettingRow(label = stringResource(R.string.debug_row_note), value = note, tag = "debug-note")
            SettingRow(label = stringResource(R.string.debug_row_cents), value = cents, tag = "debug-cents")
            SettingRow(label = stringResource(R.string.debug_row_hz), value = hz, tag = "debug-hz")
            SettingRow(label = stringResource(R.string.debug_row_level), value = level, tag = "debug-level")
            // Sentences rather than values: stacked under a caption so they wrap as prose.
            CaptionedLine(caption = stringResource(R.string.debug_row_source), text = source, tag = "debug-source")
            CaptionedLine(caption = stringResource(R.string.debug_row_platform), text = platform, tag = "debug-platform")
            CaptionedLine(caption = stringResource(R.string.debug_row_counters), text = counters, tag = "debug-counters")
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

/** The pane's own fact: what the microphone is doing, in one sentence. */
@Composable
private fun sentence(state: CaptureState, granted: Boolean, refused: Boolean): String {
    val failure = state.failure
    return when {
        refused -> stringResource(R.string.debug_state_permission_refused)
        !granted -> stringResource(R.string.debug_state_permission_needed)
        failure != null -> stringResource(R.string.debug_state_stopped, failure.sentence())
        state.recording?.silenced == true -> stringResource(R.string.debug_state_silenced)
        state.absentDevice != null && state.info != null ->
            stringResource(R.string.debug_state_listening_substitute, state.absentDevice.deviceName, state.info.deviceName)
        state.isListening -> stringResource(R.string.debug_state_listening)
        else -> stringResource(R.string.debug_state_idle)
    }
}

@Composable
private fun InputFailure.sentence(): String = when (this) {
    is InputFailure.Busy -> stringResource(R.string.debug_failure_busy, deviceName)
    is InputFailure.Disconnected -> stringResource(R.string.debug_failure_disconnected, deviceName)
    is InputFailure.Refused -> stringResource(R.string.debug_failure_refused, deviceName, refusedRates.joinToString())
    is InputFailure.Denied -> stringResource(R.string.debug_failure_denied, deviceName)
}

private const val LOG_TAG = "ticktock.debug"
private const val NANOS_PER_MILLI = 1_000_000.0
