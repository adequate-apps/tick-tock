// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ticktock.app.R
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.components.Field
import ticktock.design.components.GlIcon
import ticktock.design.components.Icon
import ticktock.design.components.Pane

@Composable
fun TableScreen() {
    Pane(title = stringResource(R.string.pane_table), modifier = Modifier.fillMaxWidth(), tag = "table-pane") {
        Field(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().testTag("table-empty"), contentAlignment = Alignment.Center) {
                Icon(GlIcon.TABLE, tint = GridlineColors.textDisabled, size = GridlineSpacing.sp8 + GridlineSpacing.sp4)
            }
        }
    }
}
