// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ticktock.app.AppGraph
import ticktock.app.R
import ticktock.app.TickTockApp
import ticktock.app.history.ShareFiles
import ticktock.app.tune.formatA4
import ticktock.core.catalogue.Catalogue
import ticktock.core.music.Cents
import ticktock.core.music.CentsBand
import ticktock.core.music.NoteSpelling
import ticktock.core.music.Transposition
import ticktock.core.prefs.Prefs
import ticktock.data.history.*
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.*
import ticktock.design.plots.Histogram

@Composable
fun TableScreen(graph: AppGraph = TickTockApp.graph(LocalContext.current)) {
    val owner = graph.table
    val state by owner.state.collectAsStateWithLifecycle()
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    val output by graph.output.state.collectAsStateWithLifecycle()
    val jobs = rememberCoroutineScope()
    val context = LocalContext.current
    val csvLabel = stringResource(R.string.table_share_csv)
    var selected by remember { mutableStateOf<Pair<RunLabel, Int>?>(null) }
    var sessions by rememberSaveable { mutableStateOf(false) }
    var savingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<SessionRow?>(null) }
    var clearNote by remember { mutableStateOf<Triple<Scope, RunLabel, Int>?>(null) }
    var shareError by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.snapshot
    val detail = selected?.let { key -> snapshot?.rows?.find { it.label == key.first && it.midiSounding == key.second } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(owner, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            owner.activate()
            try { kotlinx.coroutines.awaitCancellation() } finally { owner.deactivate() }
        }
    }
    BackHandler(selected != null || sessions) { selected = null; sessions = false }
    val current = snapshot?.sessions?.find { it.id == snapshot.currentSessionId }
    val viewed = when (val scope = state.scope) {
        Scope.CurrentSession -> current
        is Scope.Session -> snapshot?.sessions?.find { it.id == scope.id }
        is Scope.AllHistory -> null
    }
    val scopeLabel = when (state.scope) {
        Scope.CurrentSession -> stringResource(R.string.table_session)
        is Scope.Session -> viewed?.name?.ifBlank { null } ?: stringResource(R.string.table_session)
        is Scope.AllHistory -> stringResource(R.string.table_all_history)
    }
    val comparison = if (state.scope is Scope.AllHistory) emptyList() else snapshot?.history.orEmpty()
    Pane(stringResource(if (detail != null) R.string.table_note_detail else R.string.pane_table),
        Modifier.fillMaxWidth(), tag = "table-pane") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            if (detail != null) {
                Button(stringResource(R.string.action_back), { selected = null }, tag = "table-back")
                TableLabel(detail.label)
                TableNoteDetail(detail, comparison, prefs, snapshot?.since, scopeLabel, state.scope is Scope.AllHistory)
                ButtonRow(listOf(
                    ButtonAction(stringResource(if (output.droneWanted) R.string.table_stop_drone else R.string.table_hear_drone), {
                        if (output.droneWanted) graph.output.stopDrone() else {
                            graph.prefs.update { it.copy(a4Hz = detail.label.a4Hz, droneMidi = detail.midiSounding) }
                            graph.output.startDrone()
                        }
                    }, tag = "table-hear"),
                    ButtonAction(stringResource(R.string.table_clear_note), {
                        val fixed = if (state.scope == Scope.CurrentSession) snapshot?.currentSessionId?.let { Scope.Session(it) } else state.scope
                        if (fixed != null) clearNote = Triple(fixed, detail.label, detail.midiSounding)
                    }, enabled = !state.busy, tag = "table-clear-note")))
            } else if (sessions) {
                Button(stringResource(R.string.action_back), { sessions = false }, tag = "sessions-back")
                if (!owner.namedSessions.allowed) TableGhost(true) else {
                    snapshot?.sessions?.filter { it.name.isNotBlank() }?.forEach { session ->
                        Field(Modifier.fillMaxWidth()) {
                            GlText(session.name, style = GridlineType.uiStrong)
                            GlText(session.equipment, style = GridlineType.small)
                            GlText(stringResource(R.string.table_session_counts, session.distinctNotes, session.attacks, session.capturedSeconds.toInt()), style = GridlineType.small)
                            ButtonRow(listOf(
                                ButtonAction(stringResource(R.string.table_open), { owner.select(Scope.Session(session.id)); sessions = false }, tag = "session-open-${session.id}"),
                                ButtonAction(stringResource(R.string.table_resume), { owner.resume(session.id); sessions = false }, enabled = !state.busy, tag = "session-resume-${session.id}"),
                                ButtonAction(stringResource(R.string.table_delete), { deleting = session }, enabled = !state.busy, tag = "session-delete-${session.id}")))
                        }
                    }
                    if (snapshot?.sessions.orEmpty().none { it.name.isNotBlank() }) TableGhost(false)
                }
            } else {
                Segmented(listOf(Segment(stringResource(R.string.table_session)), Segment(stringResource(R.string.table_all_history))),
                    if (state.scope is Scope.AllHistory) 1 else 0, {
                        selected = null
                        owner.select(if (it == 0) Scope.CurrentSession else Scope.AllHistory(prefs.includeDroneRuns))
                    }, tag = "table-scope")
                if (state.scope is Scope.AllHistory) {
                    GlText(stringResource(if (prefs.includeDroneRuns) R.string.table_drone_included else R.string.table_drone_excluded), style = GridlineType.small)
                    Segmented(listOf(Segment(stringResource(R.string.table_free_play)), Segment(stringResource(R.string.table_with_drone))),
                        if (prefs.includeDroneRuns) 1 else 0, { owner.includeDroneRuns(it == 1) }, tag = "table-context")
                }
                viewed?.let {
                    if (it.name.isNotBlank()) GlText(it.name, style = GridlineType.uiStrong)
                    if (it.equipment.isNotBlank()) GlText(it.equipment, style = GridlineType.small)
                    GlText(stringResource(R.string.table_session_counts, it.distinctNotes, it.attacks, it.capturedSeconds.toInt()), style = GridlineType.small)
                }
                snapshot?.since?.let { GlText(stringResource(R.string.table_since, tableDate(it), pluralStringResource(R.plurals.table_session_total, snapshot.scopeSessions, snapshot.scopeSessions)), style = GridlineType.small) }
                ButtonRow(listOf(
                    ButtonAction(stringResource(R.string.table_save_session), { savingId = viewed?.id }, viewed != null && !state.busy && owner.namedSessions.allowed, primary = true, tag = "table-save"),
                    ButtonAction(stringResource(R.string.table_share_csv), {
                        jobs.launch {
                            val bytes = owner.csv() ?: return@launch
                            try {
                                val intent = withContext(Dispatchers.IO) { ShareFiles.csv(context, bytes) }
                                context.startActivity(Intent.createChooser(intent, csvLabel))
                                shareError = false
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (failure: Exception) { shareError = true }
                        }
                    }, !state.busy && !state.gated && owner.export.allowed && !snapshot?.rows.isNullOrEmpty(), tag = "table-share"),
                    ButtonAction(stringResource(R.string.table_clear_session), { deleting = viewed }, viewed != null && !state.busy, tag = "table-clear")))
                if (state.gated || snapshot?.rows.isNullOrEmpty()) TableGhost(state.gated)
                if (!snapshot?.rows.isNullOrEmpty()) {
                    GlText(stringResource(R.string.table_mean_unit), style = GridlineType.small)
                    GlText(stringResource(R.string.table_compact_extent), style = GridlineType.small)
                    GlText(stringResource(R.string.table_probability), style = GridlineType.small)
                    TableBandLegend()
                    GlText(stringResource(R.string.table_dimmed), style = GridlineType.small)
                    TableSeriesLegend(scopeLabel, comparison.isNotEmpty())
                }
                snapshot?.rows?.groupBy { it.label }?.forEach { (label, rows) ->
                    TableLabel(label)
                    Field(Modifier.fillMaxWidth()) {
                        var previousRegister: Int? = null
                        rows.sortedBy { it.midiSounding }.forEach { note ->
                            val register = tableRegister(note, prefs)
                            if (register != previousRegister) {
                                GlText(stringResource(listOf(R.string.table_low, R.string.table_middle, R.string.table_high)[register]), style = GridlineType.small)
                                previousRegister = register
                            }
                            val name = tableNoteName(note, prefs)
                            val mean = Cents.shown(note.meanCents)
                            val description = stringResource(R.string.table_row_spoken, spokenNote(name), spokenCents(mean),
                                Cents.shown(note.spreadCents) ?: 0,
                                pluralStringResource(R.plurals.table_attack_total, note.attacks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), note.attacks),
                                pluralStringResource(R.plurals.table_reading_total, note.n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), note.n))
                            HistoryRow(name, "${pluralStringResource(R.plurals.table_attack_total, note.attacks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), note.attacks)}\n${pluralStringResource(R.plurals.table_reading_total, note.n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), note.n)}", description, note.n < MIN_TABLE_READINGS,
                                { selected = note.label to note.midiSounding }, tag = "table-note-${note.midiSounding}",
                                mean = { TableMean(mean, note.n < MIN_TABLE_READINGS) }, chart = {
                                    TableHistogram(note, comparison, true, scopeLabel)
                                })
                        }
                    }
                }
                ButtonRow(listOf(
                    ButtonAction(stringResource(R.string.table_sessions), { sessions = true }, tag = "table-sessions"),
                    ButtonAction(stringResource(R.string.table_new_session), { owner.newSession() }, !state.busy, tag = "table-new")))

            }
            state.error?.let { GlText(it, color = GridlineColors.danger) }
            if (shareError) GlText(stringResource(R.string.table_share_error), color = GridlineColors.danger)
        }
    }
    snapshot?.sessions?.find { it.id == savingId }?.let { session -> SaveSessionDialog(session, prefs.lastEquipment, state.busy, state.error, { savingId = null }) { name, equipment, purpose, notes ->
        jobs.launch { owner.save(session.id, name, equipment, purpose, notes).join(); if (owner.state.value.error == null) savingId = null }
    } }
    deleting?.let { session -> TableConfirmation(stringResource(R.string.table_delete_session),
        session.name.ifBlank { stringResource(R.string.table_session) }, state.busy, state.error, { deleting = null }) {
        jobs.launch { owner.delete(session.id).join(); if (owner.state.value.error == null) { deleting = null; selected = null } }
    } }
    clearNote?.let { (scope, label, midi) -> TableConfirmation(stringResource(R.string.table_clear_note),
        detail?.let { tableNoteName(it, prefs) }.orEmpty(), state.busy, state.error, { clearNote = null }, details = {
            GlText(when (scope) {
                Scope.CurrentSession -> stringResource(R.string.table_current_session)
                is Scope.Session -> snapshot?.sessions?.find { it.id == scope.id }?.name?.ifBlank { null }
                    ?: stringResource(R.string.table_current_session)
                is Scope.AllHistory -> stringResource(R.string.table_all_matching)
            }, style = GridlineType.uiStrong)
            TableLabel(label)
            if (scope is Scope.AllHistory) GlText(stringResource(if (scope.includeDroneRuns) R.string.table_drone_included else R.string.table_drone_excluded), style = GridlineType.small)
            GlText(stringResource(R.string.table_confirm_note), style = GridlineType.small)
        }) {
        jobs.launch { owner.clearNote(scope, label, midi).join(); if (owner.state.value.error == null) { clearNote = null; selected = null } }
    } }
}

@Composable
internal fun TableLabel(label: RunLabel) {
    val instrument = Catalogue.find(label.instrumentKey)
    GlText(instrument?.displayName ?: label.instrumentKey, style = GridlineType.uiStrong)
    GlText("${label.nickname.takeIf { it.isNotBlank() }?.plus(" · ").orEmpty()}A4 ${formatA4(label.a4Hz)} Hz", style = GridlineType.small)
}

internal fun tableNoteName(note: NoteSummary, prefs: Prefs): String {
    val instrument = Catalogue.find(note.label.instrumentKey) ?: Catalogue.get(Catalogue.DEFAULT_KEY)
    val names = NoteSpelling.resolve(instrument, prefs.spelling.accidentals)
    return musicalName(NoteSpelling.name(Transposition.writtenFromSounding(note.midiSounding, note.label.transposition), names))
}

internal fun spokenNote(name: String) = name.replace("♭", " flat ").replace("♯", " sharp ")
internal fun spokenCents(value: Int?) = if (value == null) "unavailable" else Cents.format(value).replace("-", "minus ").replace("+", "plus ")
internal fun tableDate(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

@Composable
private fun TableGhost(gated: Boolean) {
    Field(Modifier.fillMaxWidth().testTag(if (gated) "table-locked" else "table-empty")) {
        Icon(GlIcon.TABLE, tint = GridlineColors.textDisabled)
        GlText(stringResource(if (gated) R.string.table_locked else R.string.table_empty), color = GridlineColors.textDisabled)
    }
}

@Composable
internal fun TableHistogram(note: NoteSummary, history: List<NoteSummary>, compact: Boolean, scopeLabel: String) {
    Histogram(note.bins, history.find { it.label == note.label && it.midiSounding == note.midiSounding }?.bins ?: IntArray(101),
        CentsBand.IN_TUNE_MAX.toFloat(), CentsBand.CLOSE_MAX.toFloat(),
        stringResource(R.string.table_chart_spoken, scopeLabel, if (history.isEmpty()) "" else stringResource(R.string.table_history_overlay), if (compact) 25 else 50),
        Modifier.fillMaxWidth(), compact = compact, dimmed = note.n < MIN_TABLE_READINGS)
}

@Composable
internal fun TableMean(value: Int?, dimmed: Boolean) {
    val appearance = centsAppearance(value ?: 0)
    val color = if (dimmed || value == null) GridlineColors.textDisabled else appearance.ink
    Row(horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1), verticalAlignment = Alignment.CenterVertically) {
        if (value != null) Icon(appearance.cue, tint = color)
        GlText(Cents.format(value), style = GridlineType.value, color = color)
    }
}

/** Register boundaries stay fixed for an instrument as the player adds notes. */
internal fun tableRegister(note: NoteSummary, prefs: Prefs): Int {
    val instrument = Catalogue.find(note.label.instrumentKey) ?: Catalogue.get(Catalogue.DEFAULT_KEY)
    val range = ticktock.app.config.effectiveRange(instrument, prefs).keyed
    val written = Transposition.writtenFromSounding(note.midiSounding, note.label.transposition)
    return ((written - range.low) * 3 / (range.high - range.low + 1)).coerceIn(0, 2)
}

private const val MIN_TABLE_READINGS = 5
