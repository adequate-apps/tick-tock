// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ticktock.app.AppGraph
import ticktock.app.BuildConfig
import ticktock.app.R
import ticktock.core.prefs.PrefsSchema
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.*

@Composable
internal fun SettingsExtras(graph: AppGraph) {
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    val output by graph.output.state.collectAsStateWithLifecycle()
    val table by graph.table.state.collectAsStateWithLifecycle()
    val jobs = rememberCoroutineScope()
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    var bytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(graph, clearing) { bytes = withContext(Dispatchers.IO) { runCatching { graph.history.databaseBytes() }.getOrNull() } }
    Pane(stringResource(R.string.settings_practice), Modifier.fillMaxWidth(), tag = "practice-settings") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
            Caption(stringResource(R.string.settings_noise_floor))
            Stepper("${prefs.noiseFloorDb} dBFS", { graph.prefs.update { it.copy(noiseFloorDb = (it.noiseFloorDb - 1).coerceIn(PrefsSchema.NOISE_FLOOR_DB)) } },
                { graph.prefs.update { it.copy(noiseFloorDb = (it.noiseFloorDb + 1).coerceIn(PrefsSchema.NOISE_FLOOR_DB)) } },
                stringResource(R.string.settings_floor_lower), stringResource(R.string.settings_floor_raise),
                lowerEnabled = prefs.noiseFloorDb > PrefsSchema.NOISE_FLOOR_DB.first, raiseEnabled = prefs.noiseFloorDb < PrefsSchema.NOISE_FLOOR_DB.last, tag = "settings-noise-floor")
            Caption(stringResource(R.string.settings_ears_first))
            ToggleSetting(prefs.earsFirst, { value -> graph.prefs.update { it.copy(earsFirst = value) } }, "settings-ears-first")
            Caption(stringResource(R.string.settings_announce))
            ToggleSetting(prefs.announceSettledNotes, { value -> graph.prefs.update { it.copy(announceSettledNotes = value) } }, "settings-announce")
        }
    }
    Pane(stringResource(R.string.settings_timing), Modifier.fillMaxWidth(), tag = "timing-settings") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
            Caption(stringResource(R.string.settings_wired_offset))
            Stepper("${prefs.visualOffsetMs} ms", { graph.prefs.update { it.copy(visualOffsetMs = (it.visualOffsetMs - 1).coerceIn(PrefsSchema.VISUAL_OFFSET_MS)) } },
                { graph.prefs.update { it.copy(visualOffsetMs = (it.visualOffsetMs + 1).coerceIn(PrefsSchema.VISUAL_OFFSET_MS)) } },
                stringResource(R.string.settings_offset_earlier), stringResource(R.string.settings_offset_later),
                lowerEnabled = prefs.visualOffsetMs > PrefsSchema.VISUAL_OFFSET_MS.first, raiseEnabled = prefs.visualOffsetMs < PrefsSchema.VISUAL_OFFSET_MS.last, tag = "settings-visual-offset")
            Caption(stringResource(R.string.settings_bluetooth_offset))
            GlText(prefs.bluetoothVisualOffsetMs?.let { "$it ms" } ?: "—", style = GridlineType.value)
            Button(stringResource(R.string.bluetooth_measure), graph.output::beginCalibration,
                enabled = output.info?.wireless == true && !output.calibrating, tag = "settings-bluetooth-measure")
        }
    }
    Pane(stringResource(R.string.settings_storage), Modifier.fillMaxWidth(), tag = "storage-settings") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
            GlText(stringResource(R.string.settings_history_size, bytes?.let { "${it / 1024} KiB" } ?: "—"), style = GridlineType.value)
            ConfigurationPanel(graph)
            Button(stringResource(R.string.settings_clear_history), { clearing = true }, enabled = !table.busy, tag = "settings-clear-history")
        }
    }
    Pane(stringResource(R.string.settings_app), Modifier.fillMaxWidth(), tag = "app-settings") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            Button(stringResource(R.string.settings_diagnostics), { diagnostics = true }, Modifier.fillMaxWidth(), tag = "settings-diagnostics")
            Button(stringResource(R.string.settings_first_run), { graph.prefs.update { it.copy(firstRunComplete = false) } }, Modifier.fillMaxWidth(), tag = "settings-first-run")
            Button(stringResource(R.string.settings_about), { about = true }, Modifier.fillMaxWidth(), tag = "settings-about")
        }
    }
    if (diagnostics) DiagnosticsDialog(graph) { diagnostics = false }
    if (about) AboutDialog { about = false }
    if (clearing) TableConfirmation(stringResource(R.string.settings_clear_history), stringResource(R.string.settings_all_readings),
        table.busy, table.error, { clearing = false }) {
        jobs.launch { graph.table.clearHistory().join(); if (graph.table.state.value.error == null) clearing = false }
    }
}

@Composable
private fun ToggleSetting(value: Boolean, update: (Boolean) -> Unit, tag: String) {
    Segmented(listOf(Segment(stringResource(R.string.settings_on)), Segment(stringResource(R.string.settings_off))),
        if (value) 0 else 1, { update(it == 0) }, tag = tag)
}

@Composable
private fun AboutDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    var document by rememberSaveable { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(document) {
        text = ""
        document?.let { file -> text = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(file).bufferedReader().use { it.readText() } }.getOrElse { context.getString(R.string.settings_notice_error) }
        } }
    }
    SquareDialog(stringResource(R.string.settings_about), { if (document != null) document = null else dismiss() }, tag = "about-dialog") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            GlText(stringResource(R.string.app_name), style = GridlineType.uiStrong)
            GlText(BuildConfig.VERSION_NAME, style = GridlineType.value)
            if (document != null) {
                GlText(text.replace("`", ""), style = GridlineType.small)
                Button(stringResource(R.string.action_back), { document = null }, tag = "about-document-back")
            } else {
                Button(stringResource(R.string.settings_licence), { document = "LICENSE" }, Modifier.fillMaxWidth(), tag = "about-licence")
                Button(stringResource(R.string.settings_notices), { document = "THIRD_PARTY.md" }, Modifier.fillMaxWidth(), tag = "about-notices")
                Button(stringResource(R.string.settings_privacy), {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))); error = false }
                    catch (failure: Exception) { error = true }
                }, Modifier.fillMaxWidth(), tag = "about-privacy")
                if (error) GlText(stringResource(R.string.settings_browser_error), color = GridlineColors.danger)
                Button(stringResource(R.string.action_back), dismiss, Modifier.fillMaxWidth(), tag = "about-back")
            }
        }
    }
}

internal const val PRIVACY_URL = "https://github.com/adequate-apps/tick-tock/blob/main/docs/privacy.md"
