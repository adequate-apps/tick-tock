// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ticktock.app.R
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.components.Field
import ticktock.design.components.GlText
import ticktock.design.components.Pane
import ticktock.design.components.StatusBar
import ticktock.design.components.TitleBar

/**
 * The Gridline frame every screen lives in: the title strip extends up behind
 * the system status bar, the work area is System Gray, and the status bar at
 * the bottom always states what the microphone and the output are doing.
 */
@Composable
fun AppFrame() {
    Column(Modifier.fillMaxSize().testTag("frame")) {
        Column(Modifier.fillMaxWidth().background(GridlineColors.active).windowInsetsPadding(WindowInsets.statusBars)) {
            TitleBar(title = stringResource(R.string.app_name), state = stringResource(R.string.state_idle))
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(GridlineSpacing.sp2)) {
            Pane(title = stringResource(R.string.pane_tune), modifier = Modifier.fillMaxWidth()) {
                Field(Modifier.fillMaxWidth()) {
                    GlText(text = stringResource(R.string.empty_ready), modifier = Modifier.testTag("empty-ready"))
                }
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
