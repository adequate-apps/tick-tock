// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.BuildConfig
import ticktock.app.R
import ticktock.app.TickTockApp
import ticktock.app.debug.DebugOutputExtras
import ticktock.app.debug.DebugTuneExtras
import ticktock.app.paletteOf
import ticktock.core.prefs.Theme
import ticktock.design.components.Field
import ticktock.design.components.GlText
import ticktock.design.components.Pane
import ticktock.design.components.Segment
import ticktock.design.components.Segmented

/**
 * The three tool screens as placeholder panes. Each pane already has the
 * shape its real content will fill: a titled strip, a white field, and an
 * empty-state sentence that says what will appear and what makes it appear.
 */
@Composable
fun TuneScreen() {
    EmptyPane(title = R.string.pane_tune, text = R.string.empty_tune, tag = "tune")
    // M2's capture and output paths made visible; a release build draws nothing here, M4 and M5 remove them.
    if (BuildConfig.DEBUG) {
        DebugTuneExtras()
        DebugOutputExtras()
    }
}

@Composable
fun DroneMetronomeScreen() {
    EmptyPane(title = R.string.pane_drone, text = R.string.empty_drone, tag = "drone", state = R.string.pane_state_stopped)
    EmptyPane(title = R.string.pane_metronome, text = R.string.empty_metronome, tag = "metronome", state = R.string.pane_state_stopped)
}

@Composable
fun TableScreen() {
    EmptyPane(title = R.string.pane_table, text = R.string.empty_table, tag = "table")
}

@Composable
fun SettingsScreen() {
    val graph = TickTockApp.graph(LocalContext.current)
    val theme by graph.theme.collectAsStateWithLifecycle()
    Pane(title = stringResource(R.string.pane_theme), modifier = Modifier.fillMaxWidth(), tag = "theme-pane") {
        Segmented(
            segments = Theme.entries.map { Segment(paletteOf(it).name) },
            selectedIndex = theme.ordinal,
            onSelect = { index -> graph.prefs.update { it.copy(theme = Theme.entries[index]) } },
            tag = "theme",
        )
    }
}

@Composable
private fun EmptyPane(title: Int, text: Int, tag: String, state: Int? = null) {
    Pane(
        title = stringResource(title),
        state = state?.let { stringResource(it) },
        modifier = Modifier.fillMaxWidth(),
        tag = "$tag-pane",
    ) {
        Field(Modifier.fillMaxWidth()) {
            GlText(text = stringResource(text), modifier = Modifier.testTag("$tag-empty"))
        }
    }
}
