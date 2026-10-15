// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    action: (@Composable () -> Unit)? = null,
) {
    val (bar, background) = when (kind) {
        NoticeKind.Attention -> GridlineColors.attention to GridlineColors.yellowBg
        NoticeKind.Danger -> GridlineColors.danger to GridlineColors.redBg
        NoticeKind.Plain -> GridlineColors.active to GridlineColors.surface2
    }
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min).background(background).testTag(tag)) {
        Box(Modifier.width(GridlineSpacing.sp1).fillMaxHeight().background(bar))
        if (icon != null) {
            Icon(icon, knockout = background, modifier = Modifier.align(Alignment.CenterVertically).padding(start = GridlineSpacing.sp3))
        }
        GlText(
            text = text,
            style = GridlineType.ui,
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
                .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp2)
                .testTag("$tag-text"),
        )
        if (action != null) {
            Box(Modifier.align(Alignment.CenterVertically).padding(GridlineSpacing.sp2)) { action() }
        }
    }
}
