// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ticktock.app.AppGraph
import ticktock.app.R
import ticktock.app.audio.CaptureState
import ticktock.app.audio.Level
import ticktock.core.catalogue.Catalogue
import ticktock.core.catalogue.Instrument
import ticktock.core.config.DisplayMode
import ticktock.core.contracts.InputFailure
import ticktock.core.music.CentsBand
import ticktock.core.music.NoteSpelling
import ticktock.core.music.Pitch
import ticktock.core.music.Transposition
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.Button
import ticktock.design.components.ButtonTone
import ticktock.design.components.Caption
import ticktock.design.components.Field
import ticktock.design.components.GlIcon
import ticktock.design.components.GlText
import ticktock.design.components.Icon
import ticktock.design.components.Notice
import ticktock.design.components.NoticeKind
import ticktock.design.components.Segment
import ticktock.design.components.Segmented
import ticktock.design.components.Stepper
import ticktock.design.plots.CentsScale
import ticktock.design.plots.LevelMeter
import ticktock.design.plots.Trace
import kotlin.math.abs

/** Why the readout cannot show a reading, and so what its notice offers. */
enum class Gate { MIC_NOT_ASKED, MIC_DENIED, MIC_DENIED_ALWAYS, MIC_BUSY, NO_INPUT }

/** The gate in force, or null when the microphone is open (a silent room is no gate: the readout simply rests). */
fun gateOf(mic: MicState, capture: CaptureState): Gate? = when {
    mic == MicState.NOT_ASKED -> Gate.MIC_NOT_ASKED
    mic == MicState.DENIED -> Gate.MIC_DENIED
    mic == MicState.DENIED_ALWAYS -> Gate.MIC_DENIED_ALWAYS
    capture.failure is InputFailure.Busy || capture.recording?.silenced == true -> Gate.MIC_BUSY
    capture.failure != null -> Gate.NO_INPUT
    else -> null
}

/**
 * The Tune pane (mockup 01): the readout with the note, its cents and their
 * band, the needle, the level, the trace and the four control groups. With
 * nothing to read the same layout stands in its ghost state; a gate puts its
 * cause icon and the key that clears it in a notice inside the readout field
 * (mockups 04a to 04d), and nothing below it moves.
 *
 * Only this pane reads the readout; the level and the trace are read where
 * they are drawn, so a held note recomposes nothing at hop rate (POL-31).
 */
@Composable
fun TuneScreen(graph: AppGraph, mic: MicAccess) {
    val capture by graph.tune.state.collectAsStateWithLifecycle()
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    val level = graph.tune.level.collectAsStateWithLifecycle()
    val trace = graph.tune.trace.collectAsStateWithLifecycle()
    val gate = gateOf(mic.state, capture)
    val listening = gate == null && capture.isListening
    val instrument = Catalogue.find(prefs.instrumentKey) ?: Catalogue.get(Catalogue.DEFAULT_KEY)
    val stacked = LocalDensity.current.fontScale >= TWO_UP_LIMIT

    // Ears first: the cents and the needle stay hidden until the note has been held for two seconds.
    val readout = if (listening) capture.readout else ticktock.app.audio.Readout()
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(readout.midi, prefs.earsFirst) {
        revealed = false
        if (readout.midi != null && prefs.earsFirst) {
            delay(REVEAL_MILLIS)
            revealed = true
        }
    }
    val hidden = prefs.earsFirst && !revealed && readout.midi != null

    Column(Modifier.fillMaxWidth().testTag("tune"), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        ReadoutField(readout.midi, readout.cents, readout.hz, prefs, instrument, hidden, gate, mic, onRetry = graph.tune::retry)
        CentsScale(
            cents = if (hidden) null else readout.cents,
            flat = stringResource(R.string.tune_flat),
            sharp = stringResource(R.string.tune_sharp),
            modifier = Modifier.fillMaxWidth(),
        )
        LevelMeter(
            db = { if (listening) level.value.db.takeIf { it > Level.LEVEL_FLOOR_DB } else null },
            caption = stringResource(R.string.tune_level),
        )
        val attacks by remember { derivedStateOf { trace.value.attacks } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Caption(stringResource(R.string.tune_trace))
            GlText(
                text = when {
                    !listening -> "—"
                    attacks == 1 -> stringResource(R.string.tune_attack_one)
                    else -> stringResource(R.string.tune_attacks, attacks)
                },
                style = GridlineType.value,
                modifier = Modifier.testTag("tune-attacks"),
            )
        }
        Trace(
            points = { trace.value.cents },
            timeLabels = listOf(
                0f to stringResource(R.string.trace_minus_4),
                0.5f to stringResource(R.string.trace_minus_2),
                1f to stringResource(R.string.trace_now),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Controls(prefs, graph, listening, stacked)
    }
}

@Composable
private fun ReadoutField(
    soundingMidi: Int?,
    cents: Int?,
    hz: Double?,
    prefs: Prefs,
    instrument: Instrument,
    hidden: Boolean,
    gate: Gate?,
    mic: MicAccess,
    onRetry: () -> Unit,
) {
    val accidentals = NoteSpelling.resolve(instrument, prefs.spelling.accidentals)
    val written = prefs.displayMode == DisplayMode.WRITTEN
    val shownMidi = soundingMidi?.let { if (written) Transposition.writtenFromSounding(it, instrument.transposition) else it }
    val otherMidi = soundingMidi?.let { if (written) it else Transposition.writtenFromSounding(it, instrument.transposition) }
    val stacked = LocalDensity.current.fontScale >= STACK_LIMIT

    Field(Modifier.fillMaxWidth().testTag("readout")) {
        val left: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
                NoteName(shownMidi, accidentals)
                GlText(
                    text = hz?.let { "%.1f Hz".format(java.util.Locale.ROOT, it) } ?: "— Hz",
                    style = GridlineType.value,
                    color = if (hz == null) GridlineColors.textDisabled else GridlineColors.textBody,
                    modifier = Modifier.testTag("readout-hz"),
                )
            }
        }
        val right: @Composable () -> Unit = {
            Column(horizontalAlignment = if (stacked) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1)) {
                if (hidden) {
                    Icon(GlIcon.EYE_SLASH, size = GridlineSpacing.sp8 + GridlineSpacing.sp4, description = stringResource(R.string.tune_hidden))
                } else {
                    CentsReadout(cents)
                }
                if (instrument.transposition != 0 && otherMidi != null) {
                    val name = spell(otherMidi, accidentals)
                    GlText(
                        text = stringResource(if (written) R.string.tune_concert else R.string.tune_written, name),
                        style = GridlineType.value,
                        color = GridlineColors.textSecondary,
                        modifier = Modifier.testTag("readout-other"),
                    )
                }
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
                left()
                right()
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                left()
                right()
            }
        }
        if (gate != null) {
            Notice(
                text = when (gate) {
                    Gate.MIC_BUSY -> stringResource(R.string.tune_held)
                    Gate.NO_INPUT -> stringResource(R.string.tune_no_input)
                    else -> ""
                },
                kind = if (gate == Gate.MIC_BUSY) NoticeKind.Attention else NoticeKind.Plain,
                icon = if (gate == Gate.MIC_BUSY) GlIcon.MIC_BUSY else GlIcon.MIC_SLASH,
                tag = "gate",
                modifier = Modifier.testTag("gate-${gate.name.lowercase()}"),
            ) {
                when (gate) {
                    Gate.MIC_NOT_ASKED, Gate.MIC_DENIED ->
                        Button(stringResource(R.string.tune_allow_mic), mic.request, tone = ButtonTone.Primary, tag = "allow-mic")
                    Gate.MIC_DENIED_ALWAYS ->
                        Button(stringResource(R.string.tune_open_settings), mic.openSettings, tone = ButtonTone.Primary, tag = "open-settings-mic")
                    Gate.MIC_BUSY, Gate.NO_INPUT ->
                        Button(stringResource(R.string.tune_try_again), onRetry, tone = ButtonTone.Primary, tag = "try-again")
                }
            }
        }
    }
}

/** The note in the readout's large type, the octave smaller beside it; a gray dash with nothing to read. */
@Composable
private fun NoteName(midi: Int?, accidentals: ticktock.core.music.Accidentals) {
    if (midi == null) {
        GlText(text = "—", style = GridlineType.note, color = GridlineColors.textDisabled, modifier = Modifier.testTag("readout-note"))
        return
    }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.testTag("readout-note")) {
        GlText(text = letter(midi, accidentals), style = GridlineType.note, maxLines = 1)
        GlText(text = Pitch.octave(midi).toString(), style = GridlineType.octave, maxLines = 1)
    }
}

/** The cents with the band's cue before them and the band's word under them; colour never stands alone (A11Y-03). */
@Composable
private fun CentsReadout(cents: Int?) {
    if (cents == null) {
        GlText(text = "—", style = GridlineType.cents, color = GridlineColors.textDisabled, modifier = Modifier.testTag("readout-cents"))
        return
    }
    val band = CentsBand.of(cents)
    val (numberColour, wordColour, word) = when (band) {
        CentsBand.IN_TUNE -> Triple(GridlineColors.positive, GridlineColors.positive, stringResource(R.string.tune_in_tune))
        CentsBand.CLOSE -> Triple(GridlineColors.attention, GridlineColors.attentionInk, stringResource(R.string.tune_close))
        CentsBand.OFF -> Triple(GridlineColors.danger, GridlineColors.danger, stringResource(R.string.tune_off))
    }
    val cue = when (band) {
        CentsBand.IN_TUNE -> GlIcon.CHECK
        CentsBand.CLOSE -> if (cents > 0) GlIcon.CUE_SHARP else GlIcon.CUE_FLAT
        CentsBand.OFF -> if (cents > 0) GlIcon.CUE_SHARP_2 else GlIcon.CUE_FLAT_2
    }
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
            Icon(cue, tint = numberColour)
            GlText(text = signed(cents), style = GridlineType.cents, color = numberColour, maxLines = 1, modifier = Modifier.testTag("readout-cents"))
        }
        GlText(text = word, style = GridlineType.uiStrong, color = wordColour, modifier = Modifier.testTag("readout-band"))
    }
}

@Composable
private fun Controls(prefs: Prefs, graph: AppGraph, listening: Boolean, stacked: Boolean) {
    val capture: @Composable () -> Unit = {
        ControlGroup(stringResource(R.string.tune_capture)) {
            Segmented(
                segments = listOf(
                    Segment(stringResource(R.string.tune_capture_on), running = listening && !prefs.capturePaused),
                    Segment(stringResource(R.string.tune_capture_paused)),
                ),
                selectedIndex = if (prefs.capturePaused) 1 else 0,
                onSelect = { i -> graph.prefs.update { it.copy(capturePaused = i == 1) } },
                tag = "capture",
            )
        }
    }
    val needle: @Composable () -> Unit = {
        ControlGroup(stringResource(R.string.tune_needle)) {
            Segmented(
                segments = listOf(Segment(stringResource(R.string.tune_needle_show)), Segment(stringResource(R.string.tune_needle_ears))),
                selectedIndex = if (prefs.earsFirst) 1 else 0,
                onSelect = { i -> graph.prefs.update { it.copy(earsFirst = i == 1) } },
                tag = "needle",
            )
        }
    }
    val names: @Composable () -> Unit = {
        ControlGroup(stringResource(R.string.tune_names)) {
            Segmented(
                segments = listOf(Segment(stringResource(R.string.tune_names_written)), Segment(stringResource(R.string.tune_names_concert))),
                selectedIndex = if (prefs.displayMode == DisplayMode.WRITTEN) 0 else 1,
                onSelect = { i -> graph.prefs.update { it.copy(displayMode = if (i == 0) DisplayMode.WRITTEN else DisplayMode.CONCERT) } },
                tag = "names",
            )
        }
    }
    val a4: @Composable () -> Unit = {
        ControlGroup(stringResource(R.string.tune_a4)) {
            Stepper(
                value = "${formatA4(prefs.a4Hz)} Hz",
                onLower = { graph.prefs.update { it.copy(a4Hz = (it.a4Hz - 1).coerceIn(PrefsSchema.A4_HZ)) } },
                onRaise = { graph.prefs.update { it.copy(a4Hz = (it.a4Hz + 1).coerceIn(PrefsSchema.A4_HZ)) } },
                lowerLabel = stringResource(R.string.tune_a4_lower),
                raiseLabel = stringResource(R.string.tune_a4_raise),
                lowerEnabled = prefs.a4Hz > PrefsSchema.A4_HZ.start,
                raiseEnabled = prefs.a4Hz < PrefsSchema.A4_HZ.endInclusive,
                tag = "a4",
            )
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp4)) {
        if (stacked) {
            capture()
            needle()
            names()
            a4()
        } else {
            TwoUp(capture, needle)
            TwoUp(names, a4)
        }
    }
}

@Composable
private fun TwoUp(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        Column(Modifier.weight(1f)) { left() }
        Column(Modifier.weight(1f)) { right() }
    }
}

@Composable
private fun ControlGroup(caption: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1)) {
        Caption(caption)
        content()
    }
}

/** "F♯", "B♭": the spelling with real accidental glyphs. */
fun letter(midi: Int, accidentals: ticktock.core.music.Accidentals): String =
    NoteSpelling.letter(midi, accidentals).replace("#", "♯").replace("b", "♭")

/** The full name with its octave, as the readout's second line prints it: "A5", "B♭3". */
fun spell(midi: Int, accidentals: ticktock.core.music.Accidentals): String = letter(midi, accidentals) + Pitch.octave(midi)

/** Signed whole cents with a true minus: "+4", "−7", "0". */
fun signed(cents: Int): String = when {
    cents > 0 -> "+$cents"
    cents < 0 -> "−${abs(cents)}"
    else -> "0"
}

/** A4 as the stepper and the status bar print it: whole hertz without a decimal. */
fun formatA4(hz: Double): String = if (hz == Math.rint(hz)) hz.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, hz)

/** From this font scale the two-up control rows become one column (foundations section 1). */
private const val TWO_UP_LIMIT = 1.3f

/** From this font scale the readout stacks the note over the cents. */
private const val STACK_LIMIT = 2.0f

private const val REVEAL_MILLIS = 2_000L
