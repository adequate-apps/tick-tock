// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.AppGraph
import ticktock.app.config.effectiveRange
import ticktock.core.ranges.RangeResolver
import ticktock.app.R
import ticktock.app.audio.CaptureState
import ticktock.app.audio.LastNote
import ticktock.app.audio.Level
import ticktock.app.audio.RangeHint
import ticktock.app.audio.RangeHintDirection
import ticktock.app.audio.TraceSnapshot
import ticktock.core.catalogue.Catalogue
import ticktock.core.catalogue.Instrument
import ticktock.core.config.DisplayMode
import ticktock.core.contracts.InputFailure
import ticktock.core.music.Cents
import ticktock.core.music.CentsBand
import ticktock.core.music.NoteSpelling
import ticktock.core.music.Pitch
import ticktock.core.music.Transposition
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.Line
import ticktock.design.frame
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
fun TuneScreen(graph: AppGraph, mic: MicAccess, onInstrument: () -> Unit) {
    val capture by graph.tune.state.collectAsStateWithLifecycle()
    val publication by graph.prefs.publication.collectAsStateWithLifecycle()
    val prefs = publication.prefs
    val rangeHint by graph.tune.rangeHint.collectAsStateWithLifecycle()
    val level = graph.tune.level.collectAsStateWithLifecycle()
    val trace = graph.tune.trace.collectAsStateWithLifecycle()
    val gate = gateOf(mic.state, capture)
    val listening = gate == null && capture.isListening
    val visibleHint = rangeHint?.takeIf { listening && it.matches(publication) }
    val instrument = Catalogue.find(prefs.instrumentKey) ?: Catalogue.get(Catalogue.DEFAULT_KEY)
    val stacked = LocalDensity.current.fontScale >= TWO_UP_LIMIT

    val live = if (listening) capture.readout else ticktock.app.audio.Readout()
    val lastNote by graph.tune.lastNote.collectAsStateWithLifecycle()

    // Ears first (EAR-01, EAR-02): while a note sounds its cents, Hz and needle
    // stay hidden; when it ends, its held mean shows for two seconds, then hides.
    val seenSerial = remember { mutableStateOf(lastNote?.serial) }
    var reveal by remember { mutableStateOf<LastNote?>(null) }
    val countdown = remember { Animatable(1f) }
    LaunchedEffect(lastNote?.serial, prefs.earsFirst, live.midi) {
        val ended = lastNote
        if (!prefs.earsFirst || live.midi != null || ended == null || ended.serial == seenSerial.value) {
            seenSerial.value = ended?.serial
            reveal = null
            return@LaunchedEffect
        }
        seenSerial.value = ended.serial
        reveal = ended
        countdown.snapTo(1f)
        countdown.animateTo(0f, tween(REVEAL_MILLIS, easing = LinearEasing))
        reveal = null
    }
    // A new note ends the reveal at once: its own reading is the one to hide.
    val shownReveal = reveal?.takeIf { live.midi == null && listening }
    val earsFirst = prefs.earsFirst
    val hidden = earsFirst && shownReveal == null && live.midi != null
    val readout = when {
        shownReveal != null -> ticktock.app.audio.Readout(
            midi = shownReveal.midi,
            cents = Cents.shown(shownReveal.meanCents, shownReveal.meanHz),
            hz = ticktock.app.audio.Readout.shownHz(shownReveal.meanHz),
        )
        hidden -> live.copy(cents = null, hz = null)
        else -> live
    }

    val spoken = readout.midi?.let { midi -> readout.cents?.let { cents ->
        val shown = shownMidi(midi, prefs, instrument)
        val name = letter(shown, NoteSpelling.resolve(instrument, prefs.spelling.accidentals))
            .replace("♭", " flat").replace("♯", " sharp") + " " + Pitch.octave(shown)
        stringResource(when { Cents.sharp(cents) -> R.string.tune_spoken_sharp; Cents.sharp(-cents) -> R.string.tune_spoken_flat; else -> R.string.tune_spoken_even }, name, kotlin.math.abs(cents))
    } }

    Column(Modifier.fillMaxWidth().testTag("tune"), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        ReadoutField(
            readout.midi, readout.cents, readout.hz, prefs, instrument, hidden, gate, mic,
            onRetry = graph.tune::retry,
            countdown = if (shownReveal != null) ({ countdown.value }) else null,
            rangeHint = visibleHint,
            onInstrument = onInstrument,
        )
        Box(Modifier.fillMaxWidth()) {
            CentsScale(
                inTuneMax = CentsBand.IN_TUNE_MAX.toFloat(),
                closeMax = CentsBand.CLOSE_MAX.toFloat(),
                cents = readout.cents,
                flat = stringResource(R.string.tune_flat),
                sharp = stringResource(R.string.tune_sharp),
                modifier = Modifier.fillMaxWidth().testTag("cents-scale").clearAndSetSemantics { },
            )
            NeedleAccessibility(stringResource(R.string.tune_needle), spoken ?: "—", Modifier.matchParentSize())
        }
        SettledAnnouncement(readout.midi, spoken, prefs.announceSettledNotes && !hidden)
        LevelMeter(
            db = { if (listening) level.value.db.takeIf { it > Level.LEVEL_FLOOR_DB } else null },
            caption = stringResource(R.string.tune_level),
        )
        if (earsFirst) {
            LastNoteTrace(lastNote, revealing = shownReveal != null, prefs, instrument)
        } else {
            LiveTrace(trace, listening)
        }
        Controls(prefs, graph, listening, stacked, hasTransposition = instrument.transposition != 0)
    }
}

/** The live trace (TRC-01): the last five seconds of cents and how many notes began in them. */
@Composable
private fun LiveTrace(trace: State<TraceSnapshot>, listening: Boolean) {
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
        inTuneMax = CentsBand.IN_TUNE_MAX.toFloat(),
        closeMax = CentsBand.CLOSE_MAX.toFloat(),
        points = { trace.value.cents },
        timeLabels = listOf(
            0f to stringResource(R.string.trace_minus_5),
            0.6f to stringResource(R.string.trace_minus_2),
            1f to stringResource(R.string.trace_now),
        ),
        description = stringResource(R.string.trace_description),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Ears first's trace (mockups 02, 02b): the last note that ended, from its
 * attack to its end, so the player sees the shape only after hearing it.
 * Its name and held mean head it; while its reading shows above, the
 * attack's settled cents take the mean's place.
 */
@Composable
private fun LastNoteTrace(note: LastNote?, revealing: Boolean, prefs: Prefs, instrument: Instrument) {
    val accidentals = NoteSpelling.resolve(instrument, prefs.spelling.accidentals)
    val summary = note?.let {
        val name = spell(shownMidi(it.midi, prefs, instrument), accidentals)
        val seconds = stringResource(R.string.tune_seconds, "%.1f".format(java.util.Locale.ROOT, it.seconds))
        val settled = it.settledCents?.let { c -> Cents.shown(c) }
        when {
            !revealing -> "$name ${signed(Cents.shown(it.meanCents, it.meanHz) ?: 0)} · $seconds"
            settled != null -> stringResource(R.string.tune_attack_settled, signed(settled)) + " · $seconds"
            else -> stringResource(R.string.tune_attack_settled, "—") + " · $seconds"
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Caption(stringResource(R.string.tune_last_note))
        GlText(text = summary ?: "—", style = GridlineType.value, modifier = Modifier.testTag("last-note"))
    }
    Trace(
        inTuneMax = CentsBand.IN_TUNE_MAX.toFloat(),
        closeMax = CentsBand.CLOSE_MAX.toFloat(),
        points = { note?.points ?: TraceSnapshot.EMPTY.cents },
        timeLabels = listOf(0f to stringResource(R.string.trace_attack), 1f to stringResource(R.string.trace_end)),
        description = stringResource(R.string.trace_last_note_description),
        modifier = Modifier.fillMaxWidth(),
        tag = "last-note-trace",
    )
}

/** The note the readout names for the sounding [midi]: written or concert as Names says. */
private fun shownMidi(midi: Int, prefs: Prefs, instrument: Instrument): Int =
    if (prefs.displayMode == DisplayMode.WRITTEN) Transposition.writtenFromSounding(midi, instrument.transposition) else midi

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
    countdown: (() -> Float)?,
    rangeHint: RangeHint?,
    onInstrument: () -> Unit,
) {
    val accidentals = NoteSpelling.resolve(instrument, prefs.spelling.accidentals)
    val written = prefs.displayMode == DisplayMode.WRITTEN
    val shownMidi = soundingMidi?.let { if (written) Transposition.writtenFromSounding(it, instrument.transposition) else it }
    val otherMidi = soundingMidi?.let { if (written) it else Transposition.writtenFromSounding(it, instrument.transposition) }
    val stacked = LocalDensity.current.fontScale >= STACK_LIMIT
    val dimmed = soundingMidi != null && !RangeResolver.inExtendedRange(instrument, effectiveRange(instrument, prefs), soundingMidi)

    Field(Modifier.fillMaxWidth().testTag("readout")) {
        val left: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
                NoteName(shownMidi, accidentals, dimmed)
                GlText(
                    text = hz?.let { "%.1f Hz".format(java.util.Locale.ROOT, it) } ?: "— Hz",
                    style = GridlineType.value,
                    color = if (hz == null || dimmed) GridlineColors.textDisabled else GridlineColors.textBody,
                    modifier = Modifier.testTag("readout-hz"),
                )
            }
        }
        val right: @Composable () -> Unit = {
            Column(horizontalAlignment = if (stacked) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1)) {
                if (hidden) {
                    Icon(GlIcon.EYE_SLASH, size = GridlineSpacing.sp8 + GridlineSpacing.sp4, description = stringResource(R.string.tune_hidden))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
                        Box(Modifier.size(GridlineSpacing.sp3).background(GridlineColors.live).frame(Line(GridlineMetrics.line, GridlineColors.textBody)))
                        GlText(text = stringResource(R.string.tune_sounding), style = GridlineType.uiStrong, modifier = Modifier.testTag("readout-sounding"))
                    }
                } else {
                    CentsReadout(cents, countdown, dimmed)
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
                    Gate.MIC_DENIED, Gate.MIC_DENIED_ALWAYS -> stringResource(R.string.tune_denied)
                    else -> ""
                },
                kind = when (gate) {
                    Gate.MIC_BUSY, Gate.MIC_DENIED -> NoticeKind.Attention
                    Gate.MIC_DENIED_ALWAYS -> NoticeKind.Danger
                    else -> NoticeKind.Plain
                },
                icon = if (gate == Gate.MIC_BUSY) GlIcon.MIC_BUSY else GlIcon.MIC_SLASH,
                iconDescription = stringResource(when (gate) {
                    Gate.MIC_DENIED, Gate.MIC_DENIED_ALWAYS -> R.string.status_mic_denied
                    Gate.MIC_BUSY -> R.string.status_mic_busy
                    else -> R.string.status_mic_off
                }),
                tag = "gate-${gate.name.lowercase()}",
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
        } else if (rangeHint != null) {
            RangeHintNotice(rangeHint, onInstrument)
        }
    }
}

@Composable
private fun RangeHintNotice(hint: RangeHint, onInstrument: () -> Unit) {
    val name = stringResource(when (hint.instrumentKey) {
        "eb_sopranino" -> R.string.range_hint_sopranino
        "bb_soprano" -> R.string.range_hint_soprano
        "eb_alto" -> R.string.range_hint_alto
        "bb_tenor" -> R.string.range_hint_tenor
        "eb_bari" -> R.string.range_hint_baritone
        "bb_bass" -> R.string.range_hint_bass_sax
        "eb_contrabass" -> R.string.range_hint_contrabass_sax
        else -> R.string.range_hint_instrument
    })
    val cause = stringResource(when (hint.direction) {
        RangeHintDirection.BELOW -> R.string.range_hint_below
        RangeHintDirection.ABOVE -> R.string.range_hint_above
        RangeHintDirection.OUTSIDE -> R.string.range_hint_outside
    }, name)
    Notice(
        text = cause,
        modifier = Modifier.padding(top = GridlineSpacing.sp3),
        kind = NoticeKind.Attention,
        icon = GlIcon.RANGE,
        tag = "range-hint",
    ) {
        Box {
            Button(stringResource(R.string.setting_instrument), onInstrument, tone = ButtonTone.Primary,
                labelPadding = GridlineSpacing.sp8, tag = "range-hint-instrument")
            Icon(GlIcon.CHEVRON, Modifier.align(Alignment.CenterEnd).padding(end = GridlineSpacing.sp2),
                tint = GridlineColors.selectionFg)
        }
    }
}

/** The note in the readout's large type, the octave smaller beside it; a gray dash with nothing to read. */
@Composable
private fun NoteName(midi: Int?, accidentals: ticktock.core.music.Accidentals, dimmed: Boolean) {
    if (midi == null) {
        GlText(text = "—", style = GridlineType.note, color = GridlineColors.textDisabled, modifier = Modifier.testTag("readout-note"))
        return
    }
    val ink = if (dimmed) GridlineColors.textDisabled else GridlineColors.textBody
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.testTag("readout-note")) {
        GlText(text = letter(midi, accidentals), style = GridlineType.note, color = ink, maxLines = 1, modifier = Modifier.testTag("readout-letter"))
        GlText(text = Pitch.octave(midi).toString(), style = GridlineType.octave, color = ink, maxLines = 1, modifier = Modifier.testTag("readout-octave"))
    }
}

/** The cents with the band's cue before them and the band's word under them; colour never stands alone (A11Y-03). */
@Composable
private fun CentsReadout(cents: Int?, countdown: (() -> Float)?, dimmed: Boolean) {
    if (cents == null) {
        GlText(text = "—", style = GridlineType.cents, color = GridlineColors.textDisabled, modifier = Modifier.testTag("readout-cents"))
        return
    }
    val (numberColour, wordColour, word, cue) = ticktock.app.screens.centsAppearance(cents)
    val numberInk = if (dimmed) GridlineColors.textDisabled else numberColour
    val wordInk = if (dimmed) GridlineColors.textDisabled else wordColour
    Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
            Icon(cue, tint = numberInk)
            GlText(text = signed(cents), style = GridlineType.cents, color = numberInk, maxLines = 1, modifier = Modifier.testTag("readout-cents"))
        }
        if (countdown != null) RevealCountdown(countdown)
        GlText(text = word, style = GridlineType.uiStrong, color = wordInk, modifier = Modifier.testTag("readout-band"))
    }
}

/** The reveal's two seconds running out, read where it is drawn so the countdown recomposes nothing. */
@Composable
private fun RevealCountdown(remaining: () -> Float) {
    val track = GridlineColors.borderLight
    val fill = GridlineColors.live
    Box(
        Modifier.fillMaxWidth().height(GridlineSpacing.sp1).testTag("reveal-countdown").drawBehind {
            drawRect(track)
            drawRect(fill, size = Size(size.width * remaining().coerceIn(0f, 1f), size.height))
        },
    )
}

@Composable
private fun Controls(prefs: Prefs, graph: AppGraph, listening: Boolean, stacked: Boolean, hasTransposition: Boolean) {
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
            if (hasTransposition) names()
            a4()
        } else {
            TwoUp(capture, needle)
            if (hasTransposition) TwoUp(names, a4) else a4()
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

/** Signed whole cents as core prints them ("+4", "+0"), with a true minus sign for print: "−7". */
fun signed(shown: Int): String = Cents.format(shown).replace('-', '−')

/** A4 as the stepper and the status bar print it: whole hertz without a decimal. */
fun formatA4(hz: Double): String = if (hz == Math.rint(hz)) hz.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, hz)

/** From this font scale the two-up control rows become one column (foundations section 1). */
private const val TWO_UP_LIMIT = 1.3f

/** From this font scale the readout stacks the note over the cents. */
private const val STACK_LIMIT = 2.0f

private const val REVEAL_MILLIS = 2_000
