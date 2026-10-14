// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType

/**
 * The Active Blue strip that names a pane or the app. [state] sits at the
 * right edge and is always a short fact ("Listening", "Idle"), never a slogan.
 * [tag] prefixes the test tags so nested strips stay distinguishable.
 */
@Composable
fun TitleBar(
    title: String,
    modifier: Modifier = Modifier,
    state: String? = null,
    tag: String = "titlebar",
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GridlineColors.active)
            .defaultMinSize(minHeight = GridlineSpacing.titleBarHeight)
            .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp1)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp6),
    ) {
        GlText(text = title, style = GridlineType.titleBar, modifier = Modifier.testTag("$tag-title"))
        Spacer(Modifier.weight(1f))
        if (state != null) {
            GlText(text = state, style = GridlineType.titleBar, modifier = Modifier.testTag("$tag-state"))
        }
        trailing()
    }
}
