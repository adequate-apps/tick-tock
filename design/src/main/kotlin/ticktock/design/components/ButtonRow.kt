// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType

data class ButtonAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true,
    val primary: Boolean = false, val tag: String = "button")

/** Actions share a row only when their complete labels fit at the player's text size. */
@Composable
fun ButtonRow(actions: List<ButtonAction>, modifier: Modifier = Modifier) {
    if (actions.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = GridlineType.uiStrong
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = GridlineSpacing.sp2
        val widest = with(density) { actions.maxOf { measurer.measure(it.label, style, maxLines = 1).size.width }.toDp() } +
            GridlineSpacing.sp4 * 2 + GridlineMetrics.line * 4
        val columns = ((maxWidth + gap) / (widest + gap)).toInt().coerceIn(1, actions.size)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            actions.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { action -> Button(action.label, action.onClick, Modifier.weight(1f),
                        if (action.primary) ButtonTone.Primary else ButtonTone.Default, action.enabled, action.tag) }
                }
            }
        }
    }
}
