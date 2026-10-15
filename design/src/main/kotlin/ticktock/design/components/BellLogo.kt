// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/**
 * The one-colour bell from the launcher icon, for the app strip (logo rules,
 * foundations section 6): the bell filled and outlined in [tint], the
 * clapper and the bar cut out of it in [background] with their outlines kept.
 * The view is cropped to the bell's stroke box plus 2 units, so at 24 dp the
 * mark is the bell itself.
 */
@Composable
fun BellLogo(tint: Color, background: Color, modifier: Modifier = Modifier, size: Dp = ICON_SIZE) {
    val paths = remember {
        val parse = { d: String -> PathParser().parsePathString(d).toPath() }
        Triple(parse(BELL), parse(CLAPPER), parse(BAR))
    }
    Canvas(modifier.size(size)) {
        scale(this.size.width / CROP_SIZE, pivot = Offset.Zero) {
            translate(-CROP_X, -CROP_Y) {
                val outline = Stroke(width = STROKE, join = StrokeJoin.Round)
                drawPath(paths.first, tint)
                drawPath(paths.first, tint, style = outline)
                for (cut in listOf(paths.second, paths.third)) {
                    drawPath(cut, tint, style = outline)
                    drawPath(cut, background)
                }
            }
        }
    }
}

private const val BELL = "M50 26h8v4h2a4 4 0 0 1 4 4v10c0 10 12 12 12 24H32c0-12 12-14 12-24V34a4 4 0 0 1 4-4h2z"
private const val CLAPPER = "M52 48h4v30h-4z"
private const val BAR = "M30 68h48v4H30z"
private const val STROKE = 5.2f
private const val CROP_X = 22f
private const val CROP_Y = 20f
private const val CROP_SIZE = 64f
