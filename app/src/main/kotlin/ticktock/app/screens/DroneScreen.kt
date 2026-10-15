// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import ticktock.app.output.OutputState
import ticktock.core.catalogue.Catalogue
import ticktock.core.catalogue.EnsembleNote
import ticktock.core.catalogue.PadMark
import ticktock.core.catalogue.TuningNotes
import ticktock.core.config.DisplayMode
import ticktock.core.contracts.ParkReason
import ticktock.core.contracts.Voice
import ticktock.core.music.NoteSpelling
import ticktock.core.music.Pitch
import ticktock.core.music.Transposition
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.Line
import ticktock.design.frame
import ticktock.design.components.*
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DroneScreen() {
    val graph = TickTockApp.graph(LocalContext.current)
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    val state by graph.output.state.collectAsStateWithLifecycle()
    val instrument = Catalogue.get(prefs.instrumentKey)
    val tuning = TuningNotes.forInstrument(instrument)
    val accidentals = NoteSpelling.resolve(instrument, prefs.spelling.accidentals)
    val written = prefs.displayMode == DisplayMode.WRITTEN
    val sounding = graph.output.selectedMidi(prefs)
    val shown = if (written) Transposition.writtenFromSounding(sounding, instrument.transposition) else sounding
    val other = if (written) sounding else Transposition.writtenFromSounding(sounding, instrument.transposition)
    fun name(midi: Int) = musicalName(NoteSpelling.name(midi, accidentals))
    fun play(midi: Int) { graph.output.selectMidi(midi); graph.output.startDrone() }
    val large = LocalDensity.current.fontScale >= 1.3f

    Column(Modifier.fillMaxWidth().testTag("drone-pane"), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp4)) {
        Field(Modifier.fillMaxWidth().testTag("drone-readout")) {
            val note: @Composable () -> Unit = {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        GlText(musicalName(NoteSpelling.letter(shown, accidentals)), style = GridlineType.note, modifier = Modifier.testTag("drone-note"))
                        GlText(Pitch.octave(shown).toString(), style = GridlineType.octave, modifier = Modifier.testTag("drone-readout-octave"))
                    }
                    if (instrument.transposition != 0) GlText(
                        stringResource(if (written) R.string.tune_concert else R.string.tune_written, name(other)),
                        style = GridlineType.value, color = GridlineColors.textSecondary, modifier = Modifier.testTag("drone-other"),
                    )
                }
            }
            val facts: @Composable () -> Unit = {
                Column(horizontalAlignment = if (large) Alignment.Start else Alignment.End) {
                    GlText("%.1f Hz".format(Locale.ROOT, Pitch.hzFromMidi(sounding, prefs.a4Hz)), style = GridlineType.measure, modifier = Modifier.testTag("drone-hz"))
                    if (state.droneSounding) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
                        Box(Modifier.size(GridlineSpacing.sp2).background(GridlineColors.live).frame(Line(GridlineMetrics.line, GridlineColors.textBody)))
                        GlText(stringResource(R.string.tune_sounding), modifier = Modifier.testTag("drone-sounding"))
                    }
                }
            }
            if (large) { note(); facts() } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) { note(); facts() }
        }
        OutputNotice(state)
        if (instrument.transposition != 0) {
            Caption(stringResource(R.string.tune_names))
            Segmented(listOf(Segment(stringResource(R.string.tune_names_written)), Segment(stringResource(R.string.tune_names_concert))),
                if (written) 0 else 1, { index -> graph.prefs.update { it.copy(displayMode = if (index == 0) DisplayMode.WRITTEN else DisplayMode.CONCERT) } }, tag = "drone-names")
        }
        Caption(stringResource(R.string.drone_ensemble))
        PadGrid(EnsembleNote.entries.map { ensemble ->
            val target = tuning.ensemble.getValue(ensemble)
            val label = stringResource(if (ensemble == EnsembleNote.CONCERT_BB) R.string.drone_concert_bb else R.string.drone_concert_a)
            val secondary = if (written && instrument.transposition != 0) stringResource(R.string.tune_written, name(target.writtenMidi)) else ""
            Pad(label = label, secondary = secondary,
                description = listOf(label, secondary).filter { it.isNotEmpty() }.joinToString(", "),
                outlined = target.soundingMidi == sounding)
        }, { play(tuning.ensemble.getValue(EnsembleNote.entries[it]).soundingMidi) }, maxColumns = 2, labelStyle = GridlineType.uiStrong, tag = "drone-ensemble")
        Column {
            Caption(stringResource(if (written) R.string.tune_names_written else R.string.tune_names_concert))
            GlText(instrument.displayName, style = GridlineType.value, modifier = Modifier.testTag("drone-instrument"))
        }
        val octaveStart = shown - shown.mod(12)
        val notes = (0 until 12).map { index ->
            val displayed = octaveStart + index
            if (written) Transposition.soundingFromWritten(displayed, instrument.transposition) else displayed
        }
        PadGrid(notes.map { midi ->
            val display = if (written) Transposition.writtenFromSounding(midi, instrument.transposition) else midi
            val marks = tuning.marks(midi)
            val markLabels = buildList {
                if (PadMark.ENSEMBLE in marks) add(stringResource(R.string.drone_ensemble_mark))
                if (written && PadMark.CHECK in marks) add(stringResource(R.string.drone_check))
            }
            val context = stringResource(if (written) R.string.tune_names_written else R.string.tune_names_concert)
            val secondary = if (written && instrument.transposition != 0) name(midi) else ""
            val spoken = buildList {
                add("$context ${name(display)}")
                if (secondary.isNotEmpty()) add(stringResource(R.string.tune_concert, secondary))
                addAll(markLabels)
            }.joinToString(", ")
            Pad(label = musicalName(NoteSpelling.letter(display, accidentals)), secondary = secondary,
                marks = markLabels, description = spoken, selected = midi == sounding, enabled = midi in Pitch.DETECTOR_WINDOW)
        }, { play(notes[it]) }, tag = "drone-pads")
        Caption(stringResource(R.string.drone_octave))
        Stepper(Pitch.octave(shown).toString(), { graph.output.octave(-1) }, { graph.output.octave(1) },
            stringResource(R.string.drone_octave_lower), stringResource(R.string.drone_octave_raise),
            lowerEnabled = sounding - 12 in Pitch.DETECTOR_WINDOW, raiseEnabled = sounding + 12 in Pitch.DETECTOR_WINDOW, tag = "drone-octave")
        Caption(stringResource(R.string.tool_volume))
        Stepper("${(prefs.droneGain * 100).roundToInt()} %", { graph.output.droneVolume(-0.1f) }, { graph.output.droneVolume(0.1f) },
            stringResource(R.string.tool_volume_lower), stringResource(R.string.tool_volume_raise),
            lowerEnabled = prefs.droneGain > 0f, raiseEnabled = prefs.droneGain < 1f, tag = "drone-volume")
        Caption(stringResource(R.string.drone_voice))
        val voices = listOf(Voice.SINE, Voice.WARM, Voice.ROOT_FIFTH)
        Segmented(listOf(R.string.drone_sine, R.string.drone_warm, R.string.drone_fifth).map { Segment(stringResource(it)) },
            voices.indexOf(prefs.droneVoice), { graph.output.voice(voices[it]) }, tag = "drone-voice")
        Button(stringResource(R.string.drone_next_check), { graph.output.nextCheck(); graph.output.startDrone() }, Modifier.fillMaxWidth(), tag = "drone-next")
        OutputAction(state, state.droneWanted, state.droneSounding, graph.output::toggleDrone, Modifier.fillMaxWidth(), tag = "drone-toggle")
    }
}

internal fun musicalName(text: String): String = text.replace("#", "♯").replace("b", "♭")

/** Pending route intent has its own cancellation action while output is quiet. */
@Composable
internal fun OutputAction(state: OutputState, wanted: Boolean, sounding: Boolean, toggle: () -> Unit, modifier: Modifier = Modifier, tag: String) {
    val action = when {
        !wanted -> R.string.tool_start
        sounding -> R.string.tool_stop
        state.muted || state.parked != null -> R.string.tool_cancel_resume
        state.info == null -> R.string.tool_cancel_start
        else -> R.string.tool_turn_off
    }
    val fact = when {
        !wanted -> R.string.pane_state_stopped
        sounding -> R.string.tool_running
        state.muted -> R.string.tool_muted
        state.parked != null -> R.string.tool_paused
        state.info == null -> R.string.tool_starting
        else -> R.string.tool_muted
    }
    val description = stringResource(fact)
    Button(stringResource(action), toggle, modifier.semantics { stateDescription = description }, ButtonTone.Primary, tag = tag)
}

@Composable
internal fun OutputNotice(state: OutputState) {
    val text = when {
        state.failed != null -> state.failed.let { failure ->
            if (failure.refusedRates.isEmpty()) stringResource(R.string.output_failed, failure.deviceName)
            else stringResource(R.string.output_refused, failure.deviceName, failure.refusedRates.joinToString(", "))
        }
        state.ringing -> stringResource(R.string.output_ringing)
        state.muted -> stringResource(R.string.tool_muted)
        state.parked == ParkReason.HEADPHONES_DISCONNECTED -> stringResource(R.string.output_headphones_lost)
        state.parked == ParkReason.INCOMING_CALL -> stringResource(R.string.output_phone_call)
        else -> null
    }
    if (text != null) Notice(text, kind = if (state.failed != null) NoticeKind.Danger else NoticeKind.Attention, tag = "output-notice")
}
