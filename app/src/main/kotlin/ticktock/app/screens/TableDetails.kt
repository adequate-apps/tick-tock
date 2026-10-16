// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import ticktock.design.GridlineColors
import ticktock.core.music.CentsBand
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ticktock.app.R
import ticktock.core.catalogue.Catalogue
import ticktock.core.music.Cents
import ticktock.core.music.NoteSpelling
import ticktock.core.prefs.Prefs
import ticktock.data.history.NoteSummary
import ticktock.data.history.SessionPurpose
import ticktock.data.history.SessionRow
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.*

@Composable
internal fun TableNoteDetail(note: NoteSummary, history: List<NoteSummary>, prefs: Prefs, since: Long?, scopeLabel: String, allHistory: Boolean) {
    val previous = history.find { it.label == note.label && it.midiSounding == note.midiSounding }
    val outsideExtended = !tableInRange(note, prefs, extended = true)
    Field(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            GlText(tableNoteName(note, prefs), style = GridlineType.note)
            val instrument = Catalogue.find(note.label.instrumentKey) ?: Catalogue.get(Catalogue.DEFAULT_KEY)
            val concert = musicalName(NoteSpelling.name(note.midiSounding, NoteSpelling.resolve(instrument, prefs.spelling.accidentals)))
            if (note.label.transposition != 0) GlText(stringResource(R.string.table_concert, concert), style = GridlineType.small)
            TableSeriesLegend(scopeLabel, previous != null, note.n < MIN_TABLE_READINGS || outsideExtended)
            if (allHistory || previous != null) GlText(stringResource(R.string.table_history_context,
                stringResource(if (prefs.includeDroneRuns) R.string.table_drone_included else R.string.table_drone_excluded)), style = GridlineType.small)
            GlText(stringResource(R.string.table_detail_extent), style = GridlineType.small)
            TableHistogram(note, history, false, scopeLabel, prefs)
            TableBandLegend()
            GlText(stringResource(if (outsideExtended) R.string.table_outside_extended else R.string.table_dimmed), style = GridlineType.small)
            SummaryStats(note, scopeLabel, "table-stats-selected")
            previous?.let { SummaryStats(it, stringResource(R.string.table_all_history), "table-stats-history") }
            since?.let { GlText("${stringResource(R.string.table_first)} ${tableDate(it)}", style = GridlineType.small) }
        }
    }
}

@Composable
private fun SummaryStats(note: NoteSummary, label: String, tag: String) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1)) {
        GlText(label, style = GridlineType.uiStrong)
        GlText("${stringResource(R.string.table_attacks)} ${note.attacks} · ${stringResource(R.string.table_readings)} ${note.n}", style = GridlineType.ui)
        GlText(stringResource(R.string.table_mean_spread, Cents.format(Cents.shown(note.meanCents)), Cents.shown(note.spreadCents) ?: "—"), style = GridlineType.ui)
        GlText(stringResource(R.string.table_landing_unit, Cents.format(Cents.shown(note.meanAttackCents))), style = GridlineType.ui)
    }
}

@Composable
internal fun TableSeriesLegend(selected: String, history: Boolean, dimmed: Boolean = false) {
    SeriesKey(selected, if (dimmed) GridlineColors.textDisabled else GridlineColors.accentText)
    if (history) SeriesKey(stringResource(R.string.table_all_history), GridlineColors.borderLight)
}

@Composable
private fun SeriesKey(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        Box(Modifier.size(GridlineSpacing.sp3).background(color))
        GlText(label, style = GridlineType.small)
    }
}

@Composable
internal fun TableBandLegend() {
    SeriesKey(stringResource(R.string.table_band_in_tune, CentsBand.IN_TUNE_MAX), GridlineColors.positive)
    SeriesKey(stringResource(R.string.table_band_close, CentsBand.CLOSE_MAX), GridlineColors.attention)
}

@Composable
internal fun SaveSessionDialog(session: SessionRow, lastEquipment: String, busy: Boolean, error: String?, dismiss: () -> Unit,
    save: (String, String, SessionPurpose, String) -> Unit) {
    var name by rememberSaveable(session.id) { mutableStateOf(session.name) }
    var equipment by rememberSaveable(session.id) { mutableStateOf(session.equipment.ifBlank { lastEquipment }) }
    var notes by rememberSaveable(session.id) { mutableStateOf(session.notes) }
    var purpose by rememberSaveable(session.id) { mutableStateOf(session.purpose) }
    SquareDialog(stringResource(R.string.table_save_session), { if (!busy) dismiss() }, tag = "save-session-dialog") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            GlText(stringResource(R.string.table_session_counts, session.distinctNotes, session.attacks, session.capturedSeconds.toInt()), style = GridlineType.small)
            TextField(stringResource(R.string.table_name), name, { name = it.take(ticktock.core.prefs.PrefsSchema.TEXT_MAX) }, tag = "session-name")
            GlText(stringResource(R.string.table_purpose), style = GridlineType.small)
            Segmented(listOf(R.string.table_practice, R.string.table_shop, R.string.table_equipment_test).map { Segment(stringResource(it)) },
                purpose.ordinal, { purpose = SessionPurpose.entries[it] }, tag = "session-purpose")
            TextField(stringResource(R.string.table_equipment), equipment, { equipment = it.take(ticktock.core.prefs.PrefsSchema.TEXT_MAX) }, tag = "session-equipment")
            TextField(stringResource(R.string.table_notes), notes, { notes = it.take(2000) }, multiline = true, tag = "session-notes")
            error?.let { GlText(it, color = ticktock.design.GridlineColors.danger) }
            ButtonRow(listOf(ButtonAction(stringResource(R.string.table_cancel), dismiss, !busy, tag = "session-cancel"),
                ButtonAction(stringResource(R.string.table_save), { save(name, equipment, purpose, notes) }, !busy && name.isNotBlank(), true, "session-save")))
        }
    }
}

@Composable
internal fun TableConfirmation(title: String, subject: String, busy: Boolean, error: String?, dismiss: () -> Unit, details: @Composable () -> Unit = {}, confirm: () -> Unit) {
    SquareDialog(title, { if (!busy) dismiss() }, tag = "table-confirmation") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            GlText(subject, style = GridlineType.uiStrong)
            details()
            error?.let { GlText(it, color = ticktock.design.GridlineColors.danger) }
            ButtonRow(listOf(ButtonAction(stringResource(R.string.table_cancel), dismiss, !busy, primary = true, tag = "clear-cancel"),
                ButtonAction(title, confirm, !busy, false, "clear-confirm")))
        }
    }
}
