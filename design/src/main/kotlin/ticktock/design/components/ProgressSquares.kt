// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.Line
import ticktock.design.frame
import ticktock.design.selectionOutline

/** A quiet visual sequence: moving progress never announces each beat to a screen reader. */
@Composable
fun ProgressSquares(count: Int, filled: Int, current: Int?, description: String, modifier: Modifier = Modifier, tag: String = "progress-squares", cellSize: Dp = GridlineSpacing.sp3) {
    FlowRow(modifier.testTag(tag).clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1)) {
        repeat(count) { index ->
            Box(Modifier.size(cellSize)
                .background(if (index < filled) GridlineColors.selectionBg else GridlineColors.field)
                .then(if (index < filled) Modifier.selectionOutline() else Modifier)
                .frame(Line(GridlineMetrics.line * if (index == current) 2 else 1,
                    if (index < filled && index != current && GridlineColors.selectionOutline != GridlineColors.selectionBg) GridlineColors.selectionOutline else GridlineColors.textBody)))
        }
    }
}
