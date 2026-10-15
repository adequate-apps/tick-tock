// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import ticktock.app.AppGraph
import ticktock.app.R
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.*

internal data class DiagnosticFact(val label: Int, val value: String, val tag: String)

/** Read together once a second while this page is visible; audio callbacks never compose it. */
internal fun diagnosticFacts(graph: AppGraph): List<DiagnosticFact> {
    val capture = graph.tune.state.value
    val diagnostics = graph.tune.diagnostics.value
    val output = graph.output.state.value
    val native = graph.output.diagnostics()
    val history = graph.history.status.value
    val report = capture.recording
    return listOf(
        DiagnosticFact(R.string.diag_input, capture.info?.deviceName ?: "—", "diag-input"),
        DiagnosticFact(R.string.diag_input_rate, capture.info?.sampleRate?.let { "$it Hz" } ?: "—", "diag-input-rate"),
        DiagnosticFact(R.string.diag_requested_source, capture.info?.source?.name ?: "—", "diag-requested-source"),
        DiagnosticFact(R.string.diag_actual_source, report?.source?.name ?: "—", "diag-actual-source"),
        DiagnosticFact(R.string.diag_effects, if (report?.reported == true) report.effects.joinToString().ifBlank { "None" } else "Unknown", "diag-effects"),
        DiagnosticFact(R.string.diag_silenced, if (report?.reported == true) report.silenced.toString() else "Unknown", "diag-silenced"),
        DiagnosticFact(R.string.diag_hops, diagnostics.counters.hopsAnalysed.toString(), "diag-hops"),
        DiagnosticFact(R.string.diag_dropped, diagnostics.counters.droppedHops.toString(), "diag-dropped"),
        DiagnosticFact(R.string.diag_analysis_errors, diagnostics.counters.analysisErrors.toString(), "diag-analysis-errors"),
        DiagnosticFact(R.string.diag_hop_cost, java.lang.String.format(java.util.Locale.ROOT, "%.2f ms", diagnostics.lastHopNanos / 1_000_000.0), "diag-hop-cost"),
        DiagnosticFact(R.string.diag_output, output.info?.deviceName ?: "—", "diag-output"),
        DiagnosticFact(R.string.diag_output_rate, native?.sampleRate?.let { "$it Hz" } ?: "—", "diag-output-rate"),
        DiagnosticFact(R.string.diag_frames, native?.framesRendered?.toString() ?: "—", "diag-frames"),
        DiagnosticFact(R.string.diag_underruns, native?.underruns?.toString() ?: "—", "diag-underruns"),
        DiagnosticFact(R.string.diag_late_beats, native?.lateBeatsDropped?.toString() ?: "—", "diag-late-beats"),
        DiagnosticFact(R.string.diag_buffer, native?.bufferBursts?.toString() ?: "—", "diag-buffer"),
        DiagnosticFact(R.string.diag_history, graph.history.databaseBytes().toString(), "diag-history"),
        DiagnosticFact(R.string.diag_paused, history.skippedWhilePaused.toString(), "diag-paused"),
        DiagnosticFact(R.string.diag_flushes, history.flushes.toString(), "diag-flushes"),
        DiagnosticFact(R.string.diag_store_failures, history.failedFlushes.toString(), "diag-store-failures"),
        DiagnosticFact(R.string.diag_prefs_failures, graph.prefs.failedSaves.toString(), "diag-prefs-failures"),
    )
}

@Composable
internal fun DiagnosticsDialog(graph: AppGraph, dismiss: () -> Unit) {
    var facts by remember { mutableStateOf(emptyList<DiagnosticFact>()) }
    var failed by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(graph, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try { facts = withContext(Dispatchers.IO) { diagnosticFacts(graph) }; failed = false }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { failed = true }
                delay(1_000)
            }
        }
    }
    SquareDialog(stringResource(R.string.settings_diagnostics), dismiss, tag = "diagnostics-dialog") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            facts.forEach { fact -> Field(Modifier.fillMaxWidth().testTag(fact.tag)) {
                Caption(stringResource(fact.label))
                GlText(fact.value, style = GridlineType.value)
            } }
            if (failed) GlText(stringResource(R.string.diag_read_error))
            Button(stringResource(R.string.action_back), dismiss, Modifier.fillMaxWidth(), tag = "diagnostics-back")
        }
    }
}
