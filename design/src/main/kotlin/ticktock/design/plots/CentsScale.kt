// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.plots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.components.Caption
import ticktock.design.frame
import ticktock.design.reliefInset

/**
 * The needle: a ±50 c scale with the in-tune (±5 c) and close (±12 c) bands
 * as tinted blocks behind a 4 dp pointer, so position reads before any number
 * does (foundations section 1). [cents] is the whole cent the readout prints;
 * null parks the pointer at the centre in gray, the ghost of a needle with
 * nothing to show.
 */
@Composable
fun CentsScale(cents: Int?, modifier: Modifier = Modifier, flat: String = "Flat", sharp: String = "Sharp", tag: String = "cents-scale") {
    val colors = GridlineColors
    val description = cents?.let { if (it > 0) "+$it" else "$it" } ?: "—"
    Column(
        modifier = modifier
            .background(colors.field)
            .frame()
            .padding(GridlineMetrics.line)
            .reliefInset()
            .padding(horizontal = INSET, vertical = GridlineSpacing.sp3)
            .semantics { contentDescription = description }
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = GridlineSpacing.sp6), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption(flat)
            Caption(sharp)
        }
        Canvas(Modifier.fillMaxWidth().height(BAND_HEIGHT)) {
            fun x(c: Float) = (c + RANGE) / (2 * RANGE) * size.width
            val line = GridlineMetrics.line.toPx()
            val bottom = size.height
            // Close band, then in-tune band, each with its edges ruled in its own colour.
            drawRect(colors.yellowBg, Offset(x(-CLOSE), 0f), Size(x(CLOSE) - x(-CLOSE), bottom))
            drawRect(colors.positiveBg, Offset(x(-IN_TUNE), 0f), Size(x(IN_TUNE) - x(-IN_TUNE), bottom))
            for (edge in listOf(-CLOSE, CLOSE)) drawRect(colors.attention, Offset(x(edge) - line / 2, 0f), Size(line, bottom))
            for (edge in listOf(-IN_TUNE, IN_TUNE)) drawRect(colors.positive, Offset(x(edge) - line / 2, 0f), Size(line, bottom))
            // The baseline and a tick every 5 c, long at every 25 c.
            drawRect(colors.textBody, Offset(0f, bottom - line), Size(size.width, line))
            val minor = 8.dp.toPx()
            val major = 16.dp.toPx()
            for (c in -50..50 step 5) {
                val h = if (c % 25 == 0) major else minor
                drawRect(colors.textBody, Offset(x(c.toFloat()) - line / 2, bottom - h), Size(line, h))
            }
            val pointer = POINTER_WIDTH.toPx()
            val at = (cents ?: 0).coerceIn(-50, 50).toFloat()
            drawRect(if (cents == null) colors.textDisabled else colors.textBody, Offset(x(at) - pointer / 2, 0f), Size(pointer, bottom))
        }
        ScaleLabels(
            labels = listOf(0f to "−50", 0.25f to "−25", 0.5f to "0", 0.75f to "+25", 1f to "+50"),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val RANGE = 50f
private const val CLOSE = 12f
private const val IN_TUNE = 5f
private val POINTER_WIDTH = 4.dp
private val BAND_HEIGHT = 48.dp
private val INSET = 24.dp
