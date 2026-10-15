// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.AppGraph
import ticktock.app.R
import ticktock.core.config.PersonalConfigMerge
import ticktock.core.prefs.PersonalConfiguration
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.*

@Composable
internal fun ConfigurationPanel(graph: AppGraph) {
    val owner = graph.configuration
    val state by owner.state.collectAsStateWithLifecycle()
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    var pendingImport by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(owner) {
        if (pendingImport && !owner.state.value.busy && owner.state.value.incoming == null && owner.state.value.message == null) owner.interruptedImport()
    }
    LaunchedEffect(state) {
        if (!state.busy && state.incoming == null && state.message != null) pendingImport = false
    }
    val busy = state.busy
    val message = state.message
    val failed = state.failed
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) owner.export(uri)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pendingImport = true; owner.open(uri) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        ButtonRow(listOf(
            ButtonAction(stringResource(R.string.config_export), {
                save.launch("metronome-settings.json")
            }, !busy, tag = "config-export"),
            ButtonAction(stringResource(R.string.config_import), { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, !busy, tag = "config-import")))
        message?.let { GlText(stringResource(it), style = GridlineType.small, color = if (failed) GridlineColors.danger else GridlineColors.textSecondary) }
    }
    state.incoming?.let { config ->
        val diff = PersonalConfigMerge.diff(PersonalConfiguration.snapshot(prefs), config)
        SquareDialog(stringResource(R.string.config_preview), { if (!busy) { pendingImport = false; owner.cancel() } }, tag = "config-preview") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
                if (diff.isEmpty) GlText(stringResource(R.string.config_no_changes))
                diff.changes.forEach { change ->
                    Field(Modifier.fillMaxWidth()) {
                        GlText(importLabel(change), style = GridlineType.uiStrong)
                        GlText("${change.before ?: "—"} → ${change.after}", style = GridlineType.value)
                    }
                }
                if (failed) message?.let { GlText(stringResource(it), color = GridlineColors.danger) }
                ButtonRow(listOf(ButtonAction(stringResource(R.string.action_cancel), { pendingImport = false; owner.cancel() }, !busy, tag = "config-cancel"),
                    ButtonAction(stringResource(R.string.config_apply), {
                        owner.apply()
                    }, !busy, true, "config-apply")))
            }
        }
    }
}

@Composable
private fun importLabel(change: ticktock.core.config.ImportChange): String = when (change.section) {
    ticktock.core.config.ImportChange.Section.RANGE_OVERRIDE -> ticktock.core.catalogue.Catalogue.find(change.key)?.displayName ?: change.key
    ticktock.core.config.ImportChange.Section.HORN_PROFILE -> stringResource(R.string.config_horn_profile)
    ticktock.core.config.ImportChange.Section.NEWER_VERSION -> stringResource(R.string.config_newer_fields)
    ticktock.core.config.ImportChange.Section.SETTING -> stringResource(when (change.key) {
        "instrument" -> R.string.setting_instrument
        "a4Hz" -> R.string.tune_a4
        "displayMode" -> R.string.config_note_names
        "spelling" -> R.string.setting_spelling
        "responseMode" -> R.string.setting_response
        "noiseFloorDb" -> R.string.settings_noise_floor
        "keepAwake" -> R.string.setting_keep_awake
        "earsFirst" -> R.string.settings_ears_first
        "announceSettledNotes" -> R.string.settings_announce
        "droneVoice" -> R.string.config_drone_voice
        "clickSound" -> R.string.metro_click
        "visualOffsetMs" -> R.string.settings_wired_offset
        else -> R.string.config_setting
    })
}
