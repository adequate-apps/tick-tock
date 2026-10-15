// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.constrainHeight
import ticktock.design.GridlineColors
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType

/**
 * What a notice is about, which decides its colour. Colour never stands
 * alone: the sentence says the same thing in words.
 */
enum class NoticeKind {
    /** Something the player should know before acting: a Bluetooth route, a wrong instrument. */
    Attention,

    /** Something refused or lost: a device that would not open, a failed save. */
    Danger,

    /** A fact the player should know once: a kept copy of corrupt settings. */
    Plain,
}

/**
 * A full-width strip with a thick rule on its left edge, an optional cause
 * icon, one short line (or none, when the icon and key say it all) and,
 * usually, the key that does something about it ("Try again"). It appears only
 * when the player must act, inside the pane it concerns.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    kind: NoticeKind = NoticeKind.Plain,
    tag: String = "notice",
    icon: GlIcon? = null,
    iconDescription: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val (bar, background) = when (kind) {
        NoticeKind.Attention -> GridlineColors.attention to GridlineColors.yellowBg
        NoticeKind.Danger -> GridlineColors.danger to GridlineColors.redBg
        NoticeKind.Plain -> GridlineColors.active to GridlineColors.surface2
    }
    val rule = GridlineSpacing.sp1
    Layout(
        modifier = modifier.fillMaxWidth().background(background)
            .drawBehind { drawRect(bar, size = Size(rule.toPx(), size.height)) }
            .testTag(tag),
        content = {
            Row(
                Modifier.padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3),
            ) {
                if (icon != null) Icon(icon, description = iconDescription, knockout = background)
                GlText(text, style = GridlineType.ui, modifier = Modifier.weight(1f, fill = false).testTag("$tag-text"))
            }
            if (action != null) Box(Modifier.padding(GridlineSpacing.sp2)) { action() }
        },
    ) { children, constraints ->
        val ruleWidth = rule.roundToPx()
        val available = (constraints.maxWidth - ruleWidth).coerceAtLeast(0)
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = available)
        val message = children[0].measure(childConstraints)
        val key = children.getOrNull(1)?.measure(childConstraints)
        // Both keep their readable width; the action gets its own row when space runs out.
        val stacked = key != null && message.width + key.width > available
        val height = constraints.constrainHeight(if (stacked) message.height + key!!.height else maxOf(message.height, key?.height ?: 0))
        layout(constraints.maxWidth, height) {
            message.placeRelative(ruleWidth, if (stacked) 0 else (height - message.height) / 2)
            key?.placeRelative(if (stacked) ruleWidth else constraints.maxWidth - key.width,
                if (stacked) message.height else (height - key.height) / 2)
        }
    }
}
