// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.AppGraph
import ticktock.app.R
import ticktock.app.paletteOf
import ticktock.app.tune.formatA4
import ticktock.app.tune.letter
import ticktock.core.catalogue.Catalogue
import ticktock.core.catalogue.Family
import ticktock.core.catalogue.Instrument
import ticktock.core.config.Spelling
import ticktock.core.detect.ResponseMode
import ticktock.core.music.Accidentals
import ticktock.core.music.NoteSpelling
import ticktock.core.music.Transposition
import ticktock.core.prefs.PrefsSchema
import ticktock.core.prefs.Theme
import ticktock.design.GridlineSpacing
import ticktock.design.components.Caption
import ticktock.design.components.ListHeader
import ticktock.design.components.ListRow
import ticktock.design.components.Pane
import ticktock.design.components.Segment
import ticktock.design.components.Segmented
import ticktock.design.components.SettingRow
import ticktock.design.components.Stepper
import ticktock.design.frame

/** Where Settings is: its own page, or the instrument list it opens. */
enum class SettingsPage { MAIN, INSTRUMENT }

/**
 * Settings (mockup 11): the Theme pane, then Tuning. The instrument opens
 * its own list; the choices with two or three answers are keys in place, one
 * tap each.
 */
@Composable
fun SettingsScreen(graph: AppGraph, onInstrument: () -> Unit) {
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    val instrument = Catalogue.find(prefs.instrumentKey) ?: Catalogue.get(Catalogue.DEFAULT_KEY)
    Pane(title = stringResource(R.string.pane_theme), modifier = Modifier.fillMaxWidth(), tag = "theme-pane") {
        Segmented(
            segments = Theme.entries.map { Segment(paletteOf(it).name) },
            selectedIndex = prefs.theme.ordinal,
            onSelect = { index -> graph.prefs.update { it.copy(theme = Theme.entries[index]) } },
            tag = "theme",
        )
    }
    Pane(title = stringResource(R.string.pane_tuning), modifier = Modifier.fillMaxWidth(), tag = "tuning-pane") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp4)) {
            Column(Modifier.fillMaxWidth().frame()) {
                SettingRow(
                    label = stringResource(R.string.setting_instrument),
                    value = instrument.displayName,
                    onClick = onInstrument,
                    tag = "setting-instrument",
                )
            }
            Group(stringResource(R.string.tune_a4)) {
                Stepper(
                    value = "${formatA4(prefs.a4Hz)} Hz",
                    onLower = { graph.prefs.update { it.copy(a4Hz = (it.a4Hz - 1).coerceIn(PrefsSchema.A4_HZ)) } },
                    onRaise = { graph.prefs.update { it.copy(a4Hz = (it.a4Hz + 1).coerceIn(PrefsSchema.A4_HZ)) } },
                    lowerLabel = stringResource(R.string.tune_a4_lower),
                    raiseLabel = stringResource(R.string.tune_a4_raise),
                    lowerEnabled = prefs.a4Hz > PrefsSchema.A4_HZ.start,
                    raiseEnabled = prefs.a4Hz < PrefsSchema.A4_HZ.endInclusive,
                    modifier = Modifier.fillMaxWidth(),
                    tag = "settings-a4",
                )
            }
            Group(stringResource(R.string.setting_spelling)) {
                val labels = listOf(R.string.spelling_auto, R.string.spelling_sharps, R.string.spelling_flats)
                Segmented(
                    segments = Spelling.entries.map { Segment(stringResource(labels[it.ordinal])) },
                    selectedIndex = prefs.spelling.ordinal,
                    onSelect = { i -> graph.prefs.update { it.copy(spelling = Spelling.entries[i]) } },
                    tag = "spelling",
                )
            }
            Group(stringResource(R.string.setting_response)) {
                val labels = listOf(R.string.response_fast, R.string.response_normal, R.string.response_slow)
                Segmented(
                    segments = ResponseMode.entries.map { Segment(stringResource(labels[it.ordinal])) },
                    selectedIndex = prefs.responseMode.ordinal,
                    onSelect = { i -> graph.prefs.update { it.copy(responseMode = ResponseMode.entries[i]) } },
                    tag = "response",
                )
            }
            Group(stringResource(R.string.setting_keep_awake)) {
                Segmented(
                    segments = listOf(Segment(stringResource(R.string.keep_awake_on)), Segment(stringResource(R.string.keep_awake_off))),
                    selectedIndex = if (prefs.keepAwake) 0 else 1,
                    onSelect = { i -> graph.prefs.update { it.copy(keepAwake = i == 0) } },
                    tag = "keep-awake",
                )
            }
        }
    }
    SettingsExtras(graph)
}

/**
 * Every instrument, grouped by family (mockup 10c): the name, and under it
 * the key and how it sounds against what is written. A tap chooses and
 * returns to Settings.
 */
@Composable
fun InstrumentPicker(graph: AppGraph, onPicked: () -> Unit) {
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    Pane(
        title = stringResource(R.string.pane_all_instruments),
        state = Catalogue.all.size.toString(),
        modifier = Modifier.fillMaxWidth(),
        tag = "instruments-pane",
    ) {
        Column(Modifier.fillMaxWidth().frame()) {
            if (prefs.personalConfig.hornProfiles.isNotEmpty()) {
                ListHeader(stringResource(R.string.config_horn_profiles))
                prefs.personalConfig.hornProfiles.forEach { profile ->
                    val named = Catalogue.find(profile.instrumentKey)
                    if (named != null) ListRow(title = profile.nickname, detail = named.displayName,
                        selected = profile.instrumentKey == prefs.instrumentKey && profile.nickname == prefs.hornNickname,
                        onClick = {
                            graph.prefs.update { it.copy(instrumentKey = profile.instrumentKey, hornNickname = profile.nickname.take(PrefsSchema.TEXT_MAX)) }
                            onPicked()
                        }, tag = "horn-profile-${profile.id}")
                }
            }
            for (family in Family.entries) {
                val members = Catalogue.all.filter { it.family == family }
                if (members.isEmpty()) continue
                ListHeader(family.displayName)
                for (instrument in members) {
                    ListRow(
                        title = instrument.displayName,
                        detail = "${keyOf(instrument)} · ${Transposition.describe(instrument.transposition)}",
                        selected = instrument.key == prefs.instrumentKey && prefs.hornNickname.isEmpty(),
                        onClick = {
                            graph.prefs.update { it.copy(instrumentKey = instrument.key, hornNickname = "") }
                            onPicked()
                        },
                        tag = "instrument-${instrument.key}",
                    )
                }
            }
        }
    }
}

/** The instrument's key: the note it sounds for a written C, named in its family's accidentals ("E♭", "B♭", "C"). */
private fun keyOf(instrument: Instrument): String {
    val accidentals: Accidentals = NoteSpelling.auto(instrument)
    return letter(60 + instrument.transposition, accidentals)
}

@Composable
private fun Group(caption: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1)) {
        Caption(caption)
        content()
    }
}
