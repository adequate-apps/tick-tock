// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ticktock.app.R
import ticktock.design.components.Field
import ticktock.design.components.GlText
import ticktock.design.components.Pane

/**
 * The three tool screens as placeholder panes. Each pane already has the
 * shape its real content will fill: a titled strip, a white field, and an
 * empty-state sentence that says what will appear and what makes it appear.
 */
@Composable
fun TuneScreen() {
    EmptyPane(title = R.string.pane_tune, text = R.string.empty_tune, tag = "tune")
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
    EmptyPane(title = R.string.pane_settings, text = R.string.empty_settings, tag = "settings")
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
