// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * A one-unit bevel: light on two edges and dark on the other two. Raised faces
 * are light top-left; pressed and inset faces are the reverse. This is the
 * whole of Gridline's depth cue; there is no shadow anywhere.
 */
data class Bevel(val topLeft: Color, val bottomRight: Color)

/** A one-unit frame around a box. */
data class Line(val width: Dp, val color: Color)

fun Modifier.reliefRaised(): Modifier = bevel(GridlineRelief.reliefRaised)

fun Modifier.reliefPressed(): Modifier = bevel(GridlineRelief.reliefPressed)

fun Modifier.reliefInset(): Modifier = bevel(GridlineRelief.reliefInset)

fun Modifier.bevel(bevel: Bevel): Modifier = drawBehind {
    bevelLines(GridlineMetrics.line.toPx(), bevel.topLeft, bevel.bottomRight)
}

/** The strong (ink) frame by default; pass [GridlineRelief.frame] for the quiet one. */
fun Modifier.frame(line: Line = GridlineRelief.frameStrong): Modifier = drawBehind {
    val w = line.width.toPx()
    val half = w / 2f
    drawRect(
        color = line.color,
        topLeft = Offset(half, half),
        size = Size(size.width - w, size.height - w),
        style = Stroke(width = w),
    )
}

internal fun DrawScope.bevelLines(w: Float, topLeft: Color, bottomRight: Color) {
    drawRect(color = topLeft, topLeft = Offset.Zero, size = Size(size.width, w))
    drawRect(color = topLeft, topLeft = Offset.Zero, size = Size(w, size.height))
    drawRect(color = bottomRight, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
    drawRect(color = bottomRight, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height))
}
