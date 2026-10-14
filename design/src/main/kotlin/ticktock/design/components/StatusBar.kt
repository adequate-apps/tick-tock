// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType

/**
 * The always-visible status bar. Each [segments] entry is a whole sentence or
 * fact in mono type, separated by rules; the bar never hides and never shows
 * a symbol without words beside it.
 */
@Composable
fun StatusBar(
    segments: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(GridlineColors.surface).testTag("statusbar")) {
        Box(Modifier.fillMaxWidth().height(GridlineSpacing.line).background(GridlineColors.rule))
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = GridlineSpacing.statusBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segments.forEachIndexed { index, segment ->
                GlText(
                    text = segment,
                    style = GridlineType.statusBar,
                    modifier = Modifier
                        .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp1)
                        .testTag("statusbar-$index"),
                )
                Box(
                    Modifier
                        .width(GridlineSpacing.line)
                        .height(GridlineSpacing.statusBarHeight)
                        .background(GridlineColors.rule),
                )
            }
        }
    }
}
