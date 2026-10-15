// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.constrainHeight
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType

/**
 * One line of a settings list or a table: a label on the left, its current
 * value on the right in mono, a light rule underneath. With [onClick] the
 * whole row is the target and the theme's bevel flip answers the touch.
 */
@Composable
fun SettingRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tag: String = "row",
) {
    val target = if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)
    Column(modifier = modifier.fillMaxWidth().background(GridlineColors.field).then(target).testTag(tag)) {
        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = GridlineMetrics.touchTarget)
                .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp2),
            content = {
                GlText(text = label, style = GridlineType.ui, modifier = Modifier.testTag("$tag-label"))
                GlText(text = value, style = GridlineType.value, color = GridlineColors.textSecondary,
                    modifier = Modifier.testTag("$tag-value"))
                if (onClick != null) Icon(GlIcon.CHEVRON)
            },
        ) { children, constraints ->
            val gap = GridlineSpacing.sp4.roundToPx()
            val lineGap = GridlineSpacing.sp2.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val chevron = children.getOrNull(2)?.measure(loose)
            val trailing = chevron?.let { it.width + gap } ?: 0
            val name = children[0].measure(loose)
            val current = children[1].measure(loose.copy(maxWidth = (constraints.maxWidth - trailing).coerceAtLeast(0)))
            val stacked = name.width + gap + current.width + trailing > constraints.maxWidth
            val valueHeight = maxOf(current.height, chevron?.height ?: 0)
            val height = constraints.constrainHeight(if (stacked) name.height + lineGap + valueHeight else maxOf(name.height, valueHeight))
            layout(constraints.maxWidth, height) {
                name.placeRelative(0, if (stacked) 0 else (height - name.height) / 2)
                val valueTop = if (stacked) name.height + lineGap else 0
                val rowHeight = if (stacked) valueHeight else height
                current.placeRelative(if (stacked) 0 else constraints.maxWidth - trailing - current.width,
                    valueTop + (rowHeight - current.height) / 2)
                chevron?.placeRelative(constraints.maxWidth - chevron.width, valueTop + (rowHeight - chevron.height) / 2)
            }
        }
        Box(Modifier.fillMaxWidth().height(GridlineMetrics.line).background(GridlineColors.borderLight))
    }
}
