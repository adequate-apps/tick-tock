// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Gridline relief: one-unit inset lines, light on two edges and dark on the
 * other two, drawn behind the content. Raised faces are light top-left;
 * pressed and inset fields are the reverse. There is no shadow anywhere.
 */
fun Modifier.reliefRaised(): Modifier = bevel(GridlineColors.bevelLight, GridlineColors.bevelDark)

fun Modifier.reliefPressed(): Modifier = bevel(GridlineColors.bevelDark, GridlineColors.bevelLight)

fun Modifier.reliefInset(): Modifier = bevel(GridlineColors.bevelDark, GridlineColors.bevelLight)

/** A one-unit frame in [color] around the whole box. */
fun Modifier.frame(color: Color = GridlineColors.border): Modifier = drawBehind {
    val w = GridlineSpacing.line.toPx()
    val half = w / 2f
    drawRect(
        color = color,
        topLeft = Offset(half, half),
        size = Size(size.width - w, size.height - w),
        style = Stroke(width = w),
    )
}

private fun Modifier.bevel(topLeft: Color, bottomRight: Color): Modifier = drawBehind {
    bevelLines(GridlineSpacing.line.toPx(), topLeft, bottomRight)
}

internal fun DrawScope.bevelLines(w: Float, topLeft: Color, bottomRight: Color) {
    drawRect(color = topLeft, topLeft = Offset.Zero, size = Size(size.width, w))
    drawRect(color = topLeft, topLeft = Offset.Zero, size = Size(w, size.height))
    drawRect(color = bottomRight, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
    drawRect(color = bottomRight, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height))
}
