// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType

/**
 * One choice in a list: a title and, under it, one short fact. The chosen
 * row takes the selection colours with a tick, as a chosen key does. The
 * whole row, at least 48 dp tall, is the target.
 */
@Composable
fun ListRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "list-row",
) {
    val colors = GridlineColors
    val face = if (selected) colors.selectionBg else colors.field
    val ink = if (selected) colors.selectionFg else colors.textBody
    val secondary = if (selected) colors.selectionFg else colors.textSecondary
    Column(
        modifier
            .fillMaxWidth()
            .background(face)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = GridlineMetrics.touchTarget)
                .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3),
        ) {
            Column(Modifier.weight(1f)) {
                GlText(text = title, style = if (selected) GridlineType.uiStrong else GridlineType.ui, color = ink, modifier = Modifier.testTag("$tag-title"))
                if (detail != null) GlText(text = detail, style = GridlineType.ui, color = secondary, modifier = Modifier.testTag("$tag-detail"))
            }
            if (selected) Icon(GlIcon.CHECK, tint = ink, knockout = face)
        }
        Box(Modifier.fillMaxWidth().height(GridlineMetrics.line).background(colors.borderLight))
    }
}

/** A group's name above its rows: an upper-case caption on the light gray of a head row. */
@Composable
fun ListHeader(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(GridlineColors.surface2)) {
        Box(Modifier.padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp2)) {
            Caption(text)
        }
        Box(Modifier.fillMaxWidth().height(GridlineMetrics.line).background(GridlineColors.border))
    }
}
