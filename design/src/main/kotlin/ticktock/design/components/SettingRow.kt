// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = GridlineMetrics.touchTarget)
                .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp2),
            horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlText(text = label, style = GridlineType.ui, modifier = Modifier.weight(1f).testTag("$tag-label"))
            GlText(
                text = value,
                style = GridlineType.value.copy(textAlign = TextAlign.End),
                color = GridlineColors.textSecondary,
                modifier = Modifier.testTag("$tag-value"),
            )
        }
        Box(Modifier.fillMaxWidth().height(GridlineMetrics.line).background(GridlineColors.borderLight))
    }
}
