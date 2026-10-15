// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.plots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.Caption
import ticktock.design.components.GlText
import ticktock.design.frame
import kotlin.math.roundToInt

/**
 * The input level: a caption, a bar of four cells filled in cyan to the
 * level, and the level in dB. [db] is read where it is drawn, so a level
 * that moves every hop redraws the bar and the number and recomposes nothing
 * around them (POL-31). Null shows the ghost: an empty bar and "—".
 */
@Composable
fun LevelMeter(db: () -> Double?, caption: String, modifier: Modifier = Modifier, tag: String = "level") {
    val colors = GridlineColors
    val shown by remember { derivedStateOf { db()?.roundToInt() } }
    Row(modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
        Caption(caption)
        Box(
            Modifier
                .weight(1f)
                .height(BAR_HEIGHT)
                .background(colors.field)
                .drawBehind {
                    val level = db()
                    val fraction = if (level == null) 0f else ((level - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
                    drawRect(colors.live, size = Size(size.width * fraction, size.height))
                    val line = GridlineMetrics.line.toPx()
                    for (cell in 1 until CELLS) drawRect(colors.border, Offset(size.width * cell / CELLS, 0f), Size(line, size.height))
                }
                .frame(),
        )
        GlText(text = shown?.let { "${if (it < 0) "−" else ""}${kotlin.math.abs(it)} dB" } ?: "— dB", style = GridlineType.value, maxLines = 1)
    }
}

/** The bottom of the bar: quieter than this is no level at all. */
private const val FLOOR_DB = -60.0
private const val CELLS = 4
private val BAR_HEIGHT = 16.dp
