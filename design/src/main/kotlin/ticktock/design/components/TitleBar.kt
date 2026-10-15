// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.Line
import ticktock.design.frame
import ticktock.design.titleRule

/**
 * The title strip that names a pane or the app: Active Blue in Gridline,
 * black over a rule in Pit. [state] sits at the
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
    /** A mark before the title: the app strip's bell. */
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val gap = GridlineSpacing.sp4
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .background(GridlineColors.titleBg)
            .titleRule()
            .defaultMinSize(minHeight = GridlineSpacing.titleBarHeight)
            .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp1)
            .testTag(tag),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
                leading?.invoke()
                GlText(text = title, style = GridlineType.titleBar, color = GridlineColors.titleFg, maxLines = 1, modifier = Modifier.testTag("$tag-title"))
            }
            if (state != null) {
                GlText(text = state, style = GridlineType.titleBar, color = GridlineColors.titleFg, maxLines = 1, modifier = Modifier.testTag("$tag-state"))
            } else {
                Box(Modifier)
            }
            Box { trailing() }
        },
    ) { measurables, constraints ->
        // Title on the left, the action on the right, the state just left of the
        // action. The state is the first thing to give way: when the three cannot
        // keep their gaps it is left out rather than pressed against the action.
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val action = measurables[2].measure(loose)
        val titlePlaceable = measurables[0].measure(loose.copy(maxWidth = (constraints.maxWidth - action.width - (if (action.width > 0) gapPx else 0)).coerceAtLeast(0)))
        val statePlaceable = measurables[1].measure(loose)
        val needed = titlePlaceable.width + gapPx + statePlaceable.width + (if (action.width > 0) gapPx + action.width else 0)
        val showState = state != null && needed <= constraints.maxWidth
        val height = maxOf(titlePlaceable.height, action.height, if (showState) statePlaceable.height else 0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            titlePlaceable.place(0, (height - titlePlaceable.height) / 2)
            val actionX = constraints.maxWidth - action.width
            action.place(actionX, (height - action.height) / 2)
            if (showState) {
                val stateX = (if (action.width > 0) actionX - gapPx else constraints.maxWidth) - statePlaceable.width
                statePlaceable.place(stateX, (height - statePlaceable.height) / 2)
            }
        }
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
            .frame(Line(GridlineMetrics.line, GridlineColors.titleFg))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp1)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        GlText(text = label, style = GridlineType.titleBar, color = GridlineColors.titleFg)
    }
}
