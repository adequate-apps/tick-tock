// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.plots

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import ticktock.design.GridlineColors
import ticktock.design.GridlineType
import ticktock.design.components.GlText

/**
 * Labels under a plot, each centred on its fraction of the width (0 is the
 * left end, 1 the right). A label that would overhang an end is pulled in
 * so it is never clipped.
 */
@Composable
internal fun ScaleLabels(labels: List<Pair<Float, String>>, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = { labels.forEach { (_, text) -> GlText(text = text, style = GridlineType.small, color = GridlineColors.textSecondary) } },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val centre = (labels[i].first * width).toInt()
                p.place((centre - p.width / 2).coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}
