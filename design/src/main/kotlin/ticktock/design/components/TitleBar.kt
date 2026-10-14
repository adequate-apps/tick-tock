// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.Line
import ticktock.design.frame

/**
 * The Active Blue strip that names a pane or the app. [state] sits at the
 * right edge and is always a short fact ("Listening", "Idle"), never a slogan.
 * [tag] prefixes the test tags so nested strips stay distinguishable.
 * [trailing] holds at most one [TitleBarAction].
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
        horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp4),
    ) {
        GlText(text = title, style = GridlineType.titleBar, modifier = Modifier.testTag("$tag-title"))
        Spacer(Modifier.weight(1f))
        if (state != null) {
            GlText(text = state, style = GridlineType.titleBar, modifier = Modifier.testTag("$tag-state"))
        }
        trailing()
    }
}

/**
 * A framed white-on-blue action inside a title strip, such as "Settings" or
 * "Back". The strip grows to the 48 dp touch target when it holds one; the
 * theme's bevel flip answers the touch.
 */
@Composable
fun TitleBarAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "titlebar-action",
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = GridlineMetrics.touchTarget, minHeight = GridlineMetrics.touchTarget)
            .frame(Line(GridlineMetrics.line, GridlineColors.textOnBlue))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp1)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        GlText(text = label, style = GridlineType.titleBar)
    }
}
