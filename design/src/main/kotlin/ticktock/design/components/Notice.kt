// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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

    /** A plain fact worth a line of its own: a first-run explanation, a saved copy. */
    Plain,
}

/**
 * A full-width strip with a thick rule on its left edge and one short line
 * saying what the player can do. It appears only when the player must act,
 * inside the pane it concerns.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    kind: NoticeKind = NoticeKind.Plain,
    tag: String = "notice",
) {
    val (bar, background) = when (kind) {
        NoticeKind.Attention -> GridlineColors.attention to GridlineColors.yellowBg
        NoticeKind.Danger -> GridlineColors.danger to GridlineColors.redBg
        NoticeKind.Plain -> GridlineColors.active to GridlineColors.surface2
    }
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min).background(background).testTag(tag)) {
        Box(Modifier.width(GridlineSpacing.sp1).fillMaxHeight().background(bar))
        GlText(
            text = text,
            style = GridlineType.ui,
            modifier = Modifier.weight(1f).padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp2).testTag("$tag-text"),
        )
    }
}
