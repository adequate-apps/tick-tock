// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.plots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.frame
import ticktock.design.reliefInset

/**
 * The pitch trace: the last few seconds of readings in cents, oldest at the
 * left and now at the right, on the cents scale's ±50 c with the in-tune
 * band ruled at ±5 c, the close band's edges at ±12 c and the centre dotted. [points] holds one value per hop, NaN where nothing was
 * read; it is read where it is drawn, so a trace that moves every hop
 * redraws and recomposes nothing (POL-31). [timeLabels] go under the plot
 * at their fractions of the width. TalkBack reads [description] and nothing
 * else (TRC-03).
 */
@Composable
fun Trace(
    points: () -> FloatArray,
    inTuneMax: Float,
    closeMax: Float,
    timeLabels: List<Pair<Float, String>>,
    description: String,
    modifier: Modifier = Modifier,
    tag: String = "trace",
) {
    val colors = GridlineColors
    Column(
        modifier = modifier
            .background(colors.field)
            .frame()
            .padding(GridlineMetrics.line)
            .reliefInset()
            .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp3)
            .testTag(tag)
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2),
    ) {
        Canvas(Modifier.fillMaxWidth().height(PLOT_HEIGHT)) {
            fun y(c: Float) = size.height / 2f - c.coerceIn(-RANGE, RANGE) / RANGE * size.height / 2f
            val line = GridlineMetrics.line.toPx()
            drawRect(colors.positiveBg, Offset(0f, y(inTuneMax)), Size(size.width, y(-inTuneMax) - y(inTuneMax)))
            for (edge in listOf(-closeMax, closeMax)) drawRect(colors.attention, Offset(0f, y(edge) - line / 2), Size(size.width, line))
            for (edge in listOf(-inTuneMax, inTuneMax)) drawRect(colors.positive, Offset(0f, y(edge) - line / 2), Size(size.width, line))
            drawLine(
                colors.borderLight,
                Offset(0f, y(0f)),
                Offset(size.width, y(0f)),
                strokeWidth = line,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * line, 3 * line)),
            )
            val values = points()
            if (values.size > 1) {
                val step = size.width / (values.size - 1)
                val path = Path()
                var drawing = false
                values.forEachIndexed { i, c ->
                    if (c.isNaN()) {
                        drawing = false
                    } else if (!drawing) {
                        path.moveTo(i * step, y(c))
                        drawing = true
                    } else {
                        path.lineTo(i * step, y(c))
                    }
                }
                drawPath(path, colors.textBody, style = Stroke(width = 2 * line, cap = StrokeCap.Square))
            }
        }
        ScaleLabels(timeLabels, Modifier.fillMaxWidth())
    }
}

/** The trace shows ±50 c, as the cents scale does; anything further sits on the edge. */
private const val RANGE = 50f
private val PLOT_HEIGHT = 56.dp
