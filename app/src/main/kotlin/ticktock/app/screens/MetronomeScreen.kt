// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.R
import ticktock.app.TickTockApp
import ticktock.app.output.MetrePresets
import ticktock.app.output.OutputSession
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound
import ticktock.core.metro.MetroSetup
import ticktock.core.prefs.Prefs
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.*
import kotlin.math.roundToInt

@Composable
fun MetronomeScreen() {
    val graph = TickTockApp.graph(LocalContext.current)
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    val state by graph.output.state.collectAsStateWithLifecycle()
    val output = graph.output
    var entry by rememberSaveable { mutableStateOf(false) }
    var metres by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("metronome-pane"), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
        Field(Modifier.fillMaxWidth()) {
            val number: @Composable () -> Unit = {
                NumberReadout(prefs.metroBpm, MetroSetup.BPM_MIN..MetroSetup.BPM_MAX, output::bpm, output::stepBpm,
                    { entry = true }, stringResource(R.string.metro_tempo), stringResource(R.string.metro_slower), stringResource(R.string.metro_faster), tag = "metro-bpm")
            }
            val facts: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1)) {
                    GlText(stringResource(if (prefs.metroCompoundPulse) R.string.metro_compound_bpm else R.string.metro_bpm))
                    GlText("${prefs.metroBeatsPerBar}/${prefs.metroBeatUnit} · ${prefs.metroSubdivision}", style = GridlineType.value)
                    GapProgress(output, prefs)
                }
            }
            val toggle: @Composable () -> Unit = {
                OutputAction(state, state.metroWanted, state.metroSounding, output::toggleMetro, tag = "metro-toggle")
            }
            if (LocalDensity.current.fontScale >= 1.3f) {
                number(); facts(); toggle()
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2), verticalAlignment = Alignment.CenterVertically) {
                number()
                Column(Modifier.weight(1f)) { facts() }
                toggle()
            }
        }
        PadGrid(listOf(-5, -1, 1, 5).map { Pad(if (it > 0) "+$it" else "−${-it}", description = stringResource(R.string.metro_step, it)) },
            { output.stepBpm(listOf(-5, -1, 1, 5)[it]) }, labelStyle = GridlineType.uiStrong, tag = "metro-step")
        Button(stringResource(R.string.metro_tap), output::tapTempo, Modifier.fillMaxWidth(), tag = "metro-tap")
        OutputNotice(state)
        Caption(stringResource(R.string.metro_metre))
        val presets = MetrePresets.all.take(5)
        PadGrid(presets.map { preset -> Pad("${preset.beats}/${preset.unit}", selected = prefs.metroBeatsPerBar == preset.beats && prefs.metroBeatUnit == preset.unit) } +
            Pad("…", description = stringResource(R.string.metro_more)),
            { if (it == presets.size) metres = true else output.preset(presets[it]) }, maxColumns = 6, labelStyle = GridlineType.uiStrong, tag = "metro-metre")
        if (MetrePresets.hasCompoundPulse(prefs)) {
            Caption(stringResource(R.string.metro_pulse))
            Segmented(listOf(Segment(stringResource(R.string.metro_eighth)), Segment(stringResource(R.string.metro_dotted_quarter))),
                if (prefs.metroCompoundPulse) 1 else 0, { index -> graph.prefs.update { it.copy(metroCompoundPulse = index == 1) } }, tag = "metro-pulse")
        }
        Caption(stringResource(R.string.metro_accents))
        BeatRow(output, prefs)
        Caption(stringResource(R.string.metro_subdivision))
        Segmented((1..4).map { Segment(it.toString()) }, prefs.metroSubdivision - 1,
            { index -> graph.prefs.update { it.copy(metroSubdivision = index + 1) } }, tag = "metro-subdivision")
        Caption(stringResource(R.string.metro_gap))
        Caption(stringResource(R.string.metro_bars_on))
        Stepper(prefs.metroBarsOn.toString(), { graph.prefs.update { it.copy(metroBarsOn = it.metroBarsOn - 1) } },
            { graph.prefs.update { it.copy(metroBarsOn = it.metroBarsOn + 1) } }, stringResource(R.string.metro_on_fewer), stringResource(R.string.metro_on_more),
            lowerEnabled = prefs.metroBarsOn > 1, raiseEnabled = prefs.metroBarsOn < MetroSetup.GAP_BARS_MAX, tag = "metro-bars-on")
        Caption(stringResource(R.string.metro_bars_off))
        Stepper(prefs.metroBarsOff.toString(), { graph.prefs.update { it.copy(metroBarsOff = it.metroBarsOff - 1) } },
            { graph.prefs.update { it.copy(metroBarsOff = it.metroBarsOff + 1) } }, stringResource(R.string.metro_off_fewer), stringResource(R.string.metro_off_more),
            lowerEnabled = prefs.metroBarsOff > 0, raiseEnabled = prefs.metroBarsOff < MetroSetup.GAP_BARS_MAX, tag = "metro-bars-off")
        Caption(stringResource(R.string.metro_click))
        Segmented(listOf(R.string.metro_wood, R.string.metro_tick, R.string.metro_beep).map { Segment(stringResource(it)) }, prefs.clickSound.ordinal,
            { index -> graph.prefs.update { it.copy(clickSound = ClickSound.entries[index]) } }, tag = "metro-click")
        Caption(stringResource(R.string.tool_volume))
        Stepper("${(prefs.clickGain * 100).roundToInt()} %", { graph.prefs.update { it.copy(clickGain = ticktock.app.output.steppedGain(it.clickGain, -0.1f)) } },
            { graph.prefs.update { it.copy(clickGain = ticktock.app.output.steppedGain(it.clickGain, 0.1f)) } }, stringResource(R.string.tool_volume_lower), stringResource(R.string.tool_volume_raise),
            lowerEnabled = prefs.clickGain > 0f, raiseEnabled = prefs.clickGain < 1f, tag = "metro-volume")

    }
    if (entry) TempoEntry(prefs.metroBpm, { entry = false }) { output.bpm(it); entry = false }
    if (metres) MetreDialog(prefs, output) { metres = false }
}

/** Only this row consumes the per-beat stream; labels describe the editable accent and stay steady. */
@Composable
private fun BeatRow(output: OutputSession, prefs: Prefs) {
    val beat by output.beat.collectAsStateWithLifecycle()
    val names = listOf(R.string.metro_accent, R.string.metro_normal, R.string.metro_soft, R.string.tool_muted)
    PadGrid(prefs.metroAccents.mapIndexed { index, accent ->
        Pad((index + 1).toString(), secondary = stringResource(names[accent.ordinal]),
            description = stringResource(R.string.metro_beat, index + 1, stringResource(names[accent.ordinal])),
            selected = accent == Accent.ACCENT && beat?.silentBar != true, semanticSelected = accent == Accent.ACCENT, outlined = beat?.beat == index,
            dimmed = accent == Accent.MUTE || beat?.silentBar == true)
    }, output::accent, tag = "metro-beats")
}

@Composable
private fun GapProgress(output: OutputSession, prefs: Prefs) {
    val beat by output.beat.collectAsStateWithLifecycle()
    val count = prefs.metroBarsOn + prefs.metroBarsOff
    ProgressSquares(count, prefs.metroBarsOn, beat?.bar?.mod(count), stringResource(R.string.metro_gap), tag = "metro-gap-progress")
}

@Composable
private fun TempoEntry(bpm: Int, dismiss: () -> Unit, use: (Int) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(bpm.toString()) }
    val number = draft.toIntOrNull()?.takeIf { it in MetroSetup.BPM_MIN..MetroSetup.BPM_MAX }
    SquareDialog(stringResource(R.string.metro_tempo), dismiss, tag = "tempo-dialog") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
            NumberField(draft, { draft = it.filter(Char::isDigit).take(3) }, stringResource(R.string.metro_tempo), { number?.let(use) }, tag = "tempo-entry")
            Caption("${MetroSetup.BPM_MIN}–${MetroSetup.BPM_MAX}")
            Button(stringResource(R.string.action_use), { number?.let(use) }, Modifier.fillMaxWidth(), ButtonTone.Primary, enabled = number != null, tag = "tempo-use")
            Button(stringResource(R.string.action_cancel), dismiss, Modifier.fillMaxWidth(), tag = "tempo-cancel")
        }
    }
}

@Composable
private fun MetreDialog(prefs: Prefs, output: OutputSession, dismiss: () -> Unit) {
    var beats by rememberSaveable { mutableIntStateOf(prefs.metroBeatsPerBar) }
    var unit by rememberSaveable { mutableIntStateOf(prefs.metroBeatUnit) }
    SquareDialog(stringResource(R.string.metro_metre), dismiss, tag = "metre-dialog") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            MetrePresets.all.forEachIndexed { index, preset ->
                Button(preset.label, { output.preset(preset); dismiss() }, Modifier.fillMaxWidth(), tag = "metre-preset-$index")
            }
            Caption(stringResource(R.string.metro_custom))
            Stepper(beats.toString(), { beats-- }, { beats++ }, stringResource(R.string.metro_beats_fewer), stringResource(R.string.metro_beats_more),
                lowerEnabled = beats > 1, raiseEnabled = beats < MetroSetup.BEATS_MAX, tag = "metre-beats")
            val units = listOf(2, 4, 8, 16)
            Segmented(units.map { Segment("/$it") }, units.indexOf(unit), { unit = units[it] }, tag = "metre-unit")
            Button(stringResource(R.string.action_use), { output.customMetre(beats, unit); dismiss() }, Modifier.fillMaxWidth(), ButtonTone.Primary, tag = "metre-use")
            Button(stringResource(R.string.action_cancel), dismiss, Modifier.fillMaxWidth(), tag = "metre-cancel")
        }
    }
}
