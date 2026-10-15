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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ticktock.app.AppGraph
import ticktock.app.R
import ticktock.app.config.ConfigurationFiles
import ticktock.core.config.ParseResult
import ticktock.core.config.PersonalConfig
import ticktock.core.config.PersonalConfigCodec
import ticktock.core.config.PersonalConfigMerge
import ticktock.core.prefs.PersonalConfiguration
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.*

@Composable
internal fun ConfigurationPanel(graph: AppGraph) {
    val context = LocalContext.current
    val jobs = rememberCoroutineScope()
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    var incoming by remember { mutableStateOf<PersonalConfig?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<Int?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) jobs.launch {
            busy = true
            try {
                val bytes = PersonalConfigCodec.write(PersonalConfiguration.snapshot(graph.prefs.prefs.value)).toByteArray(Charsets.UTF_8)
                withContext(Dispatchers.IO) { ConfigurationFiles.write(context, uri, bytes) }
                failed = false; message = R.string.config_exported
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed = true; message = R.string.config_write_error }
            finally { busy = false }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) jobs.launch {
            busy = true
            try {
                when (val parsed = withContext(Dispatchers.IO) { ConfigurationFiles.read(context, uri) }) {
                    is ParseResult.Ok -> { incoming = parsed.config; message = null; failed = false }
                    is ParseResult.Failed -> { failed = true; message = R.string.config_invalid }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed = true; message = R.string.config_read_error }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        ButtonRow(listOf(
            ButtonAction(stringResource(R.string.config_export), {
                save.launch("metronome-settings.json")
            }, !busy, tag = "config-export"),
            ButtonAction(stringResource(R.string.config_import), { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, !busy, tag = "config-import")))
        message?.let { GlText(stringResource(it), style = GridlineType.small, color = if (failed) GridlineColors.danger else GridlineColors.textSecondary) }
    }
    incoming?.let { config ->
        val diff = PersonalConfigMerge.diff(PersonalConfiguration.snapshot(prefs), config)
        SquareDialog(stringResource(R.string.config_preview), { if (!busy) incoming = null }, tag = "config-preview") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
                if (diff.isEmpty) GlText(stringResource(R.string.config_no_changes))
                diff.changes.forEach { change ->
                    Field(Modifier.fillMaxWidth()) {
                        GlText(importLabel(change), style = GridlineType.uiStrong)
                        GlText("${change.before ?: "—"} → ${change.after}", style = GridlineType.value)
                    }
                }
                if (failed) message?.let { GlText(stringResource(it), color = GridlineColors.danger) }
                ButtonRow(listOf(ButtonAction(stringResource(R.string.action_cancel), { incoming = null }, !busy, tag = "config-cancel"),
                    ButtonAction(stringResource(R.string.config_apply), {
                        jobs.launch {
                            busy = true
                            try {
                                graph.prefs.update { PersonalConfiguration.apply(it, config) }
                                withContext(Dispatchers.IO) { graph.prefs.flushAndWait().getOrThrow() }
                                incoming = null; failed = false; message = R.string.config_applied
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { failed = true; message = R.string.config_apply_error }
                            finally { busy = false }
                        }
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
