// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.AppGraph
import ticktock.app.R
import ticktock.app.tune.MicAccess
import ticktock.app.tune.letter
import ticktock.core.catalogue.Catalogue
import ticktock.core.music.NoteSpelling
import ticktock.core.music.Transposition
import ticktock.design.GridlineSpacing
import ticktock.design.components.Button
import ticktock.design.components.ButtonTone
import ticktock.design.components.Field
import ticktock.design.components.GlIcon
import ticktock.design.components.GlText
import ticktock.design.components.Icon
import ticktock.design.components.ListRow
import ticktock.design.components.Pane
import ticktock.design.components.SettingRow
import ticktock.design.frame

/** The first-run steps (mockups 10a to 10c). */
enum class FirstRunStep { MICROPHONE, INSTRUMENT, ALL_INSTRUMENTS }

/**
 * Two steps before the first tuning: the microphone, then the instrument.
 * The microphone step is the one place with a sentence, and it is the one a
 * player must act on: what the microphone is for stays on the phone.
 */
@Composable
fun FirstRun(graph: AppGraph, mic: MicAccess, step: FirstRunStep, onStep: (FirstRunStep) -> Unit) {
    when (step) {
        FirstRunStep.MICROPHONE -> MicrophoneStep(mic, onNext = { onStep(FirstRunStep.INSTRUMENT) })
        FirstRunStep.INSTRUMENT -> InstrumentStep(graph, onAll = { onStep(FirstRunStep.ALL_INSTRUMENTS) })
        FirstRunStep.ALL_INSTRUMENTS -> InstrumentPicker(graph) { onStep(FirstRunStep.INSTRUMENT) }
    }
}

@Composable
private fun MicrophoneStep(mic: MicAccess, onNext: () -> Unit) {
    Pane(title = stringResource(R.string.first_run_microphone), state = stringResource(R.string.first_run_step, 1, 2), modifier = Modifier.fillMaxWidth(), tag = "first-run-mic") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            Field(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp4)) {
                    Icon(GlIcon.MIC, size = GridlineSpacing.sp8 + GridlineSpacing.sp4)
                    GlText(stringResource(R.string.first_run_privacy))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
                Box(Modifier.weight(1f)) {
                    Button(
                        stringResource(R.string.tune_allow_mic),
                        onClick = {
                            mic.request()
                            onNext()
                        },
                        tone = ButtonTone.Primary,
                        modifier = Modifier.fillMaxWidth(),
                        tag = "first-run-allow",
                    )
                }
                Box(Modifier.weight(1f)) {
                    Button(stringResource(R.string.first_run_later), onClick = onNext, modifier = Modifier.fillMaxWidth(), tag = "first-run-later")
                }
            }
        }
    }
}

@Composable
private fun InstrumentStep(graph: AppGraph, onAll: () -> Unit) {
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    Pane(title = stringResource(R.string.pane_instrument), state = stringResource(R.string.first_run_step, 2, 2), modifier = Modifier.fillMaxWidth(), tag = "first-run-instrument") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            Column(Modifier.fillMaxWidth().frame()) {
                for (key in COMMON) {
                    val instrument = Catalogue.find(key) ?: continue
                    ListRow(
                        title = instrument.displayName,
                        detail = "${letter(60 + instrument.transposition, NoteSpelling.auto(instrument))} · ${Transposition.describe(instrument.transposition)}",
                        selected = instrument.key == prefs.instrumentKey && prefs.hornNickname.isEmpty(),
                        onClick = { graph.prefs.update { it.copy(instrumentKey = instrument.key, hornNickname = "") } },
                        tag = "first-run-$key",
                    )
                }
                SettingRow(
                    label = stringResource(R.string.pane_all_instruments),
                    value = Catalogue.all.size.toString(),
                    onClick = onAll,
                    tag = "first-run-all",
                )
            }
            Button(
                stringResource(R.string.first_run_start),
                onClick = { graph.prefs.update { it.copy(firstRunComplete = true) } },
                tone = ButtonTone.Primary,
                modifier = Modifier.fillMaxWidth().testTag("first-run-start"),
                tag = "first-run-start",
            )
        }
    }
}

/** The instruments most players pick, in score order; the rest are one tap away. */
private val COMMON = listOf("flute_c", "clar_bb", "eb_alto", "bb_tenor", "trp_bb", "tbn_tenor")
