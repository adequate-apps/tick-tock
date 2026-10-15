// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.BuildConfig
import ticktock.app.R
import ticktock.app.TickTockApp
import ticktock.app.debug.DebugOutputExtras
import ticktock.app.paletteOf
import ticktock.core.prefs.Theme
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.components.Field
import ticktock.design.components.GlIcon
import ticktock.design.components.GlText
import ticktock.design.components.Icon
import ticktock.design.components.Pane
import ticktock.design.components.Segment
import ticktock.design.components.Segmented

/**
 * The tools that later milestones fill (Drone and Metronome in M5, Table in
 * M6) stand as their own panes with a dimmed ghost of their icon: no prose
 * about what will appear.
 */
@Composable
fun DroneScreen() {
    GhostPane(title = R.string.pane_drone, icon = GlIcon.DRONE, tag = "drone", state = R.string.pane_state_stopped)
    // M2's output path made visible; a release build draws nothing here, M5 replaces it.
    if (BuildConfig.DEBUG) DebugOutputExtras()
}

@Composable
fun MetronomeScreen() {
    GhostPane(title = R.string.pane_metronome, icon = GlIcon.METRONOME, tag = "metronome", state = R.string.pane_state_stopped)
}

@Composable
fun TableScreen() {
    GhostPane(title = R.string.pane_table, icon = GlIcon.TABLE, tag = "table")
}

@Composable
private fun GhostPane(title: Int, icon: GlIcon, tag: String, state: Int? = null) {
    Pane(
        title = stringResource(title),
        state = state?.let { stringResource(it) },
        modifier = Modifier.fillMaxWidth(),
        tag = "$tag-pane",
    ) {
        Field(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().testTag("$tag-empty"), contentAlignment = Alignment.Center) {
                Icon(icon, tint = GridlineColors.textDisabled, size = GridlineSpacing.sp8 + GridlineSpacing.sp4)
            }
        }
    }
}
