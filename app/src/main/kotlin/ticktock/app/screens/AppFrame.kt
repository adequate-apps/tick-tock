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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ticktock.app.R
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.components.Segment
import ticktock.design.components.Segmented
import ticktock.design.components.StatusBar
import ticktock.design.components.TitleBar
import ticktock.design.components.TitleBarAction

/**
 * The Gridline frame every screen lives in. Top to bottom: the app title strip
 * (extending up behind the system status bar), the tab row, the work area in
 * System Gray, and the status bar that always states what the microphone and
 * the output are doing.
 *
 * Navigation is two pieces of saved state: which tab is open and whether
 * Settings is over it. The system back gesture closes Settings and otherwise
 * returns to the Tune tab, so the player always lands somewhere familiar.
 */
@Composable
fun AppFrame() {
    // Both survive rotation and process death: the enum saves as a Serializable.
    var tab by rememberSaveable { mutableStateOf(Tab.Tune) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = settingsOpen || tab != Tab.Tune) {
        if (settingsOpen) settingsOpen = false else tab = Tab.Tune
    }

    Column(Modifier.fillMaxSize().testTag("frame")) {
        Column(Modifier.fillMaxWidth().background(GridlineColors.active).windowInsetsPadding(WindowInsets.statusBars)) {
            if (settingsOpen) {
                TitleBar(title = stringResource(R.string.pane_settings)) {
                    TitleBarAction(label = stringResource(R.string.action_back), onClick = { settingsOpen = false }, tag = "back")
                }
            } else {
                TitleBar(title = stringResource(R.string.app_name), state = stringResource(R.string.state_idle)) {
                    TitleBarAction(label = stringResource(R.string.action_settings), onClick = { settingsOpen = true }, tag = "open-settings")
                }
            }
        }

        if (!settingsOpen) {
            Segmented(
                segments = Tab.entries.map { Segment(label = stringResource(it.label)) },
                selectedIndex = tab.ordinal,
                onSelect = { tab = Tab.entries[it] },
                tag = "tabs",
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(GridlineSpacing.sp2),
            verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2),
        ) {
            when {
                settingsOpen -> SettingsScreen()
                tab == Tab.Tune -> TuneScreen()
                tab == Tab.DroneMetronome -> DroneMetronomeScreen()
                tab == Tab.Table -> TableScreen()
            }
        }

        StatusBar(
            segments = listOf(
                stringResource(R.string.status_microphone_closed),
                stringResource(R.string.status_output_closed),
            ),
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        )
    }
}
