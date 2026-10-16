// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.core.contracts.RouteClass
import ticktock.app.R
import ticktock.app.TickTockApp
import ticktock.app.tune.MicState
import ticktock.app.tune.MicWord
import ticktock.app.tune.TuneScreen
import ticktock.app.tune.micWord
import ticktock.app.tune.rememberMicAccess
import ticktock.app.tune.statusWords
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.components.BellLogo
import ticktock.design.components.Segment
import ticktock.design.components.Segmented
import ticktock.design.components.StatusBar
import ticktock.design.components.TitleBar
import ticktock.design.components.TitleBarAction

/**
 * The Gridline frame every screen lives in. Top to bottom: the app title strip
 * (extending up behind the system status bar), the tab row, the work area in
 * System Gray, and the status bar, which shows a microphone or output fact
 * while there is one and stays empty at idle.
 *
 * Navigation is two pieces of saved state: which tab is open and whether
 * Settings is over it. The system back gesture closes Settings and otherwise
 * returns to the Tune tab, so the player always lands somewhere familiar.
 */
@Composable
fun AppFrame() {
    val graph = TickTockApp.graph(LocalContext.current)
    val mic = rememberMicAccess(graph)
    val capture = graph.tune.state.collectAsStateWithLifecycle()
    val prefs = graph.prefs.prefs.collectAsStateWithLifecycle()
    val output by graph.output.state.collectAsStateWithLifecycle()
    // The frame reads the capture only through these, so a held note's readout,
    // which changes several times a second, recomposes nothing here (POL-31).
    val micWord by remember(mic.state) { derivedStateOf { micWord(mic.state, capture.value, prefs.value) } }
    val a4Hz by remember { derivedStateOf { prefs.value.a4Hz } }
    val keepAwake by remember(output.toolRunning) { derivedStateOf { (capture.value.isListening || output.toolRunning) && prefs.value.keepAwake } }
    KeepScreenOn(keepAwake)
    SideEffect { FrameCompositions.count++ }

    // Both survive rotation and process death: the enum saves as a Serializable.
    var tab by rememberSaveable { mutableStateOf(Tab.Tune) }
    var settings by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val screenFailures = rememberScreenFailures()
    val settingsOpen = settings != null

    val firstRun by remember { derivedStateOf { !prefs.value.firstRunComplete } }
    var firstStep by rememberSaveable(firstRun) { mutableStateOf(if (mic.state == MicState.ALLOWED) FirstRunStep.INSTRUMENT else FirstRunStep.MICROPHONE) }
    BackHandler(enabled = firstRun && firstStep != FirstRunStep.MICROPHONE) {
        firstStep = if (firstStep == FirstRunStep.ALL_INSTRUMENTS) FirstRunStep.INSTRUMENT else FirstRunStep.MICROPHONE
    }

    BackHandler(enabled = !firstRun && (settingsOpen || tab != Tab.Tune)) {
        settings = when (settings) {
            SettingsPage.INSTRUMENT -> SettingsPage.MAIN
            SettingsPage.MAIN -> null
            null -> settings.also { tab = Tab.Tune }
        }
    }

    Column(Modifier.fillMaxSize().testTag("frame")) {
        Column(Modifier.fillMaxWidth().background(GridlineColors.titleBg).windowInsetsPadding(WindowInsets.statusBars)) {
            if (firstRun) {
                TitleBar(
                    title = stringResource(R.string.app_name),
                    leading = { BellLogo(tint = GridlineColors.titleFg, background = GridlineColors.titleBg) },
                )
            } else if (settingsOpen) {
                TitleBar(title = stringResource(if (settings == SettingsPage.INSTRUMENT) R.string.pane_instrument else R.string.pane_settings)) {
                    TitleBarAction(label = stringResource(R.string.action_back), onClick = { settings = if (settings == SettingsPage.INSTRUMENT) SettingsPage.MAIN else null }, tag = "back")
                }
            } else {
                TitleBar(
                    title = stringResource(R.string.app_name),
                    leading = { BellLogo(tint = GridlineColors.titleFg, background = GridlineColors.titleBg) },
                ) {
                    TitleBarAction(label = stringResource(R.string.action_settings), onClick = { settings = SettingsPage.MAIN }, tag = "open-settings")
                }
            }
        }

        if (!settingsOpen && !firstRun) {
            Segmented(
                segments = Tab.entries.map {
                    Segment(label = stringResource(it.label),
                        running = when (it) {
                            Tab.Tune -> micWord == MicWord.LISTENING || micWord == MicWord.PAUSED
                            Tab.Drone -> output.droneSounding
                            Tab.Metronome -> output.metroSounding
                            Tab.Table -> false
                        })
                },
                selectedIndex = tab.ordinal,
                onSelect = { tab = Tab.entries[it] },
                tag = "tabs",
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2),
        ) {
            val screenId = when {
                firstRun -> "setup"
                settings == SettingsPage.INSTRUMENT -> "instrument"
                settingsOpen -> "settings"
                else -> tab.tag
            }
            RecoverableScreen(screenId, screenFailures, header = {
                AppNotices(graph)
                BluetoothNotice(graph.output)
            }) {
                when {
                    firstRun -> FirstRun(graph, mic, firstStep) { firstStep = it }
                    settings == SettingsPage.INSTRUMENT -> InstrumentPicker(graph) { settings = SettingsPage.MAIN }
                    settingsOpen -> SettingsScreen(graph) { settings = SettingsPage.INSTRUMENT }
                    tab == Tab.Tune -> TuneScreen(graph, mic)
                    tab == Tab.Drone -> DroneScreen(showAction = !output.droneWanted)
                    tab == Tab.Metronome -> MetronomeScreen()
                    tab == Tab.Table -> TableScreen()
                }
            }
        }

        if (!firstRun && !settingsOpen && tab == Tab.Drone && output.droneWanted) {
            OutputAction(output, output.droneWanted, output.droneSounding, graph.output::toggleDrone,
                Modifier.fillMaxWidth().padding(GridlineSpacing.sp2), tag = "drone-toggle")
        }

        // An idle microphone and a closed output show nothing: the bar carries a
        // fact only while it is true and worth a glance (Listening, Mic busy, Bluetooth).
        StatusBar(
            segments = statusWords(micWord, a4Hz) + listOfNotNull(
                when {
                    output.info?.wireless == true -> stringResource(R.string.bluetooth_name)
                    output.info?.routeClass == RouteClass.HEADPHONES -> stringResource(R.string.status_headphones)
                    output.info?.routeClass == RouteClass.SPEAKER -> stringResource(R.string.status_speaker)
                    else -> null
                },
                if (output.muted) stringResource(R.string.tool_muted) else null,
            ),
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        )
    }
    DelayDialog(graph.output)
}

/** Keeps the screen on while [active]: the player's hands are on the instrument (keep-awake, PLAN 3.3). */
@Composable
private fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * How many times the frame has composed, for the POL-31 test: a held note must
 * not recompose the frame. Counting is one increment per composition.
 */
internal object FrameCompositions {
    @Volatile
    var count: Int = 0
}
