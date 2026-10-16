// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ticktock.design.GridlineColors

/** One mark in an icon, in the 24-unit grid of the icon sheet. */
sealed interface IconMark {
    val path: String

    /** A solid shape. */
    data class Fill(override val path: String) : IconMark

    /** A 2-unit stroke with square caps and miter joins. */
    data class Line(override val path: String) : IconMark

    /** A 6-unit stroke in the background colour, cutting a gap where a slash crosses a shape. */
    data class Knockout(override val path: String) : IconMark

    /** A solid shape in the background colour, clearing room for the mark drawn over it. */
    data class Cutout(override val path: String) : IconMark
}

private fun rect(x: Int, y: Int, w: Int, h: Int) = "M$x ${y}h${w}v${h}h-${w}z"

/**
 * The in-app icon set, drawn from the same paths as the icon sheet
 * (foundations section 5): a 24-unit grid with 2 units of padding, one
 * 2-unit stroke weight, square caps, solid shapes inset one unit so their
 * mass matches a stroke. Two sizes only, 24 dp and 48 dp.
 */
enum class GlIcon(val marks: List<IconMark>) {
    MIC(listOf(IconMark.Fill(rect(9, 2, 6, 12)), IconMark.Line("M6 11v3h12v-3M12 14v5M8 21h8"))),
    MIC_SLASH(
        listOf(
            IconMark.Fill(rect(9, 2, 6, 12)),
            IconMark.Line("M6 11v3h12v-3M12 14v5M8 21h8"),
            IconMark.Knockout("M4 3l16 18"),
            IconMark.Line("M4 3l16 18"),
        ),
    ),
    MIC_BUSY(
        listOf(
            IconMark.Fill(rect(6, 2, 6, 12)),
            IconMark.Line("M3 11v3h12v-3M9 14v5M5 21h8"),
            IconMark.Cutout(rect(12, 11, 11, 11)),
            IconMark.Fill(rect(14, 13, 8, 8)),
        ),
    ),
    CHECK(listOf(IconMark.Line("M4 13l5 5L20 7"))),
    CHEVRON(listOf(IconMark.Line("M9 5l7 7-7 7"))),
    EYE_SLASH(
        listOf(
            IconMark.Line("M2 12l10-7 10 7-10 7z"),
            IconMark.Fill(rect(10, 10, 4, 4)),
            IconMark.Knockout("M4 3l16 18"),
            IconMark.Line("M4 3l16 18"),
        ),
    ),
    BLUETOOTH(listOf(IconMark.Line("M6 6l12 12-6 4V2l6 4L6 18"))),
    DRONE(listOf(IconMark.Line("M7 2v12h10V2M12 14v7M8 21h8"))),
    METRONOME(listOf(IconMark.Line("M8 3h8l4 18H4zM12 18l4-9"))),
    TABLE(listOf(IconMark.Line("M3 4h18v16H3zM3 10h18M3 15h18M10 4v16"))),
    RANGE(listOf(IconMark.Line("M4 5H20M4 12H20"), IconMark.Fill("M12 15L17 22H7z"))),
    CUE_SHARP(listOf(IconMark.Fill("M12 6l8 12H4z"))),
    CUE_SHARP_2(listOf(IconMark.Fill("M12 2l8 9H4zM12 13l8 9H4z"))),
    CUE_FLAT(listOf(IconMark.Fill("M12 18L4 6h16z"))),
    CUE_FLAT_2(listOf(IconMark.Fill("M12 11L4 2h16zM12 22l-8-9h16z"))),
}

/**
 * An icon at [size] (24 or 48 dp) in [tint]. [knockout] is the colour behind
 * it, used where a slash cuts through a shape. [description] is what
 * TalkBack reads; null for an icon whose meaning a label beside it carries.
 */
@Composable
fun Icon(
    icon: GlIcon,
    modifier: Modifier = Modifier,
    tint: Color = GridlineColors.textBody,
    knockout: Color = GridlineColors.field,
    size: Dp = ICON_SIZE,
    description: String? = null,
) {
    val paths = remember(icon) { icon.marks.map { it to PathParser().parsePathString(it.path).toPath() } }
    val semantics = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    Canvas(modifier.size(size).then(semantics)) {
        scale(this.size.width / GRID, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            for ((mark, path) in paths) drawMark(mark, path, tint, knockout)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMark(mark: IconMark, path: Path, tint: Color, knockout: Color) {
    when (mark) {
        is IconMark.Fill -> drawPath(path, tint)
        is IconMark.Line -> drawPath(path, tint, style = Stroke(width = 2f, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        is IconMark.Knockout -> drawPath(path, knockout, style = Stroke(width = 6f, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        is IconMark.Cutout -> drawPath(path, knockout)
    }
}

/** The standard icon size; the 48 dp hero size is twice it. */
val ICON_SIZE = 24.dp
private const val GRID = 24f
