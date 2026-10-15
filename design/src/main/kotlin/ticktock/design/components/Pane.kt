// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.frame
import ticktock.design.reliefInset

/**
 * A framed System Gray pane with an Active Blue title strip. Panes are the
 * unit of layout: one per tool on a phone, two side by side in landscape.
 * [state] is the pane's own fact ("Listening", "Stopped") and sits at the
 * right end of its strip.
 */
@Composable
fun Pane(
    title: String,
    modifier: Modifier = Modifier,
    state: String? = null,
    tag: String = "pane",
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(GridlineColors.surface)
            .frame()
            .testTag(tag),
    ) {
        TitleBar(title = title, state = state, tag = "$tag-titlebar")
        Column(modifier = Modifier.fillMaxWidth().padding(GridlineSpacing.sp2), content = content)
    }
}

/**
 * A white content field with an ink border and inset relief: where readouts,
 * tables and plots live. Gray is structure; white is content.
 */
@Composable
fun Field(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(GridlineColors.field)
            .frame()
            .padding(GridlineMetrics.line)
            .reliefInset()
            .padding(GridlineSpacing.sp3),
        content = content,
    )
}
