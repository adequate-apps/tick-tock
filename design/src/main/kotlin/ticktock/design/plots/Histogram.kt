// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.plots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing

/** Distributions share a probability scale, so a longer history cannot hide the current session. */
@Composable
fun Histogram(
    bins: IntArray,
    history: IntArray = IntArray(BINS),
    inTuneMax: Float,
    closeMax: Float,
    description: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    dimmed: Boolean = false,
    tag: String = "histogram",
) {
    require(bins.size == BINS && history.size == BINS)
    val colors = GridlineColors
    val current = density(bins)
    val previous = density(history)
    val peak = maxOf(current.maxOrNull() ?: 0f, previous.maxOrNull() ?: 0f).coerceAtLeast(0.000001f)
    val span = if (compact) 25f else 50f
    Column(modifier.background(colors.field).testTag(tag).clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        Canvas(Modifier.fillMaxWidth().height(if (compact) 40.dp else 176.dp)) {
            fun x(cents: Float) = (cents + span + 0.5f) / (2 * span + 1) * size.width
            val line = GridlineMetrics.line.toPx()
            drawRect(colors.yellowBg, Offset(x(-closeMax), 0f), Size(x(closeMax) - x(-closeMax), size.height))
            drawRect(colors.positiveBg, Offset(x(-inTuneMax), 0f), Size(x(inTuneMax) - x(-inTuneMax), size.height))
            drawLine(colors.borderLight, Offset(x(0f), 0f), Offset(x(0f), size.height), line,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * line, 3 * line)))
            val width = size.width / (2 * span + 1)
            fun bars(values: FloatArray, color: androidx.compose.ui.graphics.Color) {
                for (cents in -span.toInt()..span.toInt()) {
                    val height = values[cents + BINS / 2] / peak * (size.height - line)
                    if (height > 0f) drawRect(color, Offset(x(cents.toFloat()) - width / 2, size.height - line - height),
                        Size((width - line / 2).coerceAtLeast(width / 2), height))
                }
            }
            bars(previous, colors.borderLight)
            bars(current, if (dimmed) colors.textDisabled else colors.active)
            if (!compact) drawLine(colors.textBody, Offset(0f, size.height - line), Offset(size.width, size.height - line), line)
        }
        if (!compact) ScaleLabels(listOf(0f to "−50", 0.25f to "−25", 0.5f to "0", 0.75f to "+25", 1f to "+50"), Modifier.fillMaxWidth())
    }
}

private fun density(bins: IntArray): FloatArray {
    val total = bins.sumOf { it.coerceAtLeast(0).toLong() }.coerceAtLeast(1)
    return FloatArray(bins.size) { bins[it].coerceAtLeast(0).toFloat() / total }
}

private const val BINS = 101
