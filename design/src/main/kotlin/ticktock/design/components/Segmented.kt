// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineRelief
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.Line
import ticktock.design.bevel
import ticktock.design.frame
import ticktock.design.selectionOutline

/**
 * One choice in a [Segmented] control. [running] marks a tool that is making
 * sound or listening while the player looks at another segment; it draws the
 * live cyan square beside the label.
 */
data class Segment(val label: String, val running: Boolean = false)

/**
 * A row of mutually exclusive choices, which is also the tab bar. Every
 * segment is a raised control face; the chosen one takes the selection
 * colours with pressed relief, so the row reads like a bank of keys with one
 * held down.
 *
 * Keys share the width equally while every label fits on one line with its
 * padding. When one does not (large font sizes, narrow screens) the keys wrap
 * into two columns, and into one column if even half the width is too narrow,
 * so every label stays whole and visible; nothing scrolls out of view.
 */
@Composable
fun Segmented(
    segments: List<Segment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "segmented",
) {
    val measurer = rememberTextMeasurer()
    val style = GridlineType.uiStrong
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth().testTag(tag)) {
        val widest = with(density) {
            segments.maxOfOrNull { segment ->
                val label = measurer.measure(segment.label, style, maxLines = 1).size.width.toDp()
                label + KEY_PADDING * 2 + (if (segment.running) GridlineSpacing.sp2 + GridlineSpacing.sp2 else 0.dp)
            } ?: 0.dp
        }
        val columns = columnsFor(segments.size, maxWidth, widest)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            segments.indices.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEach { index ->
                        SegmentKey(
                            segment = segments[index],
                            selected = index == selectedIndex,
                            onClick = { onSelect(index) },
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("$tag-$index"),
                        )
                    }
                    // A short last row keeps the grid's key width rather than stretching.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Every key on one row if the widest label fits its share, then two columns, then one. */
internal fun columnsFor(count: Int, width: Dp, widestKey: Dp): Int {
    if (count <= 1) return 1
    if (widestKey <= width / count) return count
    if (count > 2 && widestKey <= width / 2) return 2
    return 1
}

private val KEY_PADDING = GridlineSpacing.sp3

@Composable
private fun SegmentKey(
    segment: Segment,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val face = if (selected) GridlineColors.selectionBg else GridlineColors.controlFace
    val text = if (selected) GridlineColors.selectionFg else GridlineColors.textBody
    val relief = if (selected || pressed) GridlineRelief.reliefPressed else GridlineRelief.reliefRaised
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = GridlineMetrics.touchTarget)
            .background(face)
            // A key is a rule-bordered face with the bevel inside the border, as in
            // the mockups; in Pit, where bevels are black, the border is its edge.
            // The tap target is the whole key, border included.
            .frame(GridlineRelief.frame)
            .then(if (selected) Modifier.selectionOutline() else Modifier)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(GridlineMetrics.line)
            .bevel(relief)
            .padding(horizontal = KEY_PADDING, vertical = GridlineSpacing.sp2),
        horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (segment.running) {
            // Cyan alone is too light on a gray key (2.3:1); the ink edge in the label's own colour carries it.
            Box(Modifier.size(GridlineSpacing.sp2).background(GridlineColors.live).frame(Line(GridlineMetrics.line, text)).testTag("running"))
        }
        GlText(
            text = segment.label,
            style = GridlineType.uiStrong.copy(textAlign = TextAlign.Center),
            color = text,
            maxLines = 1,
        )
    }
}
