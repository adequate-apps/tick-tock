// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType

/** One focused action and spoken summary, with room for the distribution at large text sizes. */
@Composable
fun HistoryRow(note: String, counts: String, description: String, dimmed: Boolean, onOpen: () -> Unit,
    modifier: Modifier = Modifier, tag: String = "history-row", mean: @Composable () -> Unit, chart: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().background(GridlineColors.field).clickable(role = Role.Button, onClick = onOpen)
        .testTag(tag).clearAndSetSemantics { contentDescription = description }) {
        BoxWithConstraints(Modifier.fillMaxWidth().defaultMinSize(minHeight = GridlineMetrics.touchTarget).padding(GridlineSpacing.sp3)) {
            val stacked = maxWidth < 260.dp || LocalDensity.current.fontScale >= 1.5f
            val ink = if (dimmed) GridlineColors.textDisabled else GridlineColors.accentText
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        GlText(note, style = GridlineType.uiStrong, color = ink)
                        mean()
                    }
                    chart()
                    GlText(counts, style = GridlineType.small, color = if (dimmed) GridlineColors.textDisabled else GridlineColors.textSecondary)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2), verticalAlignment = Alignment.CenterVertically) {
                    GlText(note, style = GridlineType.uiStrong, color = ink)
                    Box(Modifier.weight(1f)) { chart() }
                    mean()
                    GlText(counts, style = GridlineType.small, color = if (dimmed) GridlineColors.textDisabled else GridlineColors.textSecondary)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(GridlineMetrics.line).background(GridlineColors.borderLight))
    }
}
