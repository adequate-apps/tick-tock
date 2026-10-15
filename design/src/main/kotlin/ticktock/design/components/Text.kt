// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import ticktock.design.GridlineColors
import ticktock.design.GridlineType

/**
 * The one text primitive. Every label in the app goes through here with a
 * Gridline style, so the type scale is the only place sizes are chosen.
 */
@Composable
fun GlText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = GridlineType.ui,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    // An explicit colour wins, then the style's own, then the theme's body ink.
    val ink = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> GridlineColors.textBody
    }
    val resolved = style.copy(color = ink)
    BasicText(text = text, modifier = modifier, style = resolved, maxLines = maxLines, overflow = overflow)
}
