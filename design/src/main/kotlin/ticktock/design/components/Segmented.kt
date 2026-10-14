// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineRelief
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.bevel

/**
 * One choice in a [Segmented] control. [running] marks a tool that is making
 * sound or listening while the player looks at another segment; it draws the
 * live cyan square beside the label.
 */
data class Segment(val label: String, val running: Boolean = false)

/**
 * A row of mutually exclusive choices, which is also the tab bar. Every
 * segment is a raised gray face; the chosen one is Active Blue with pressed
 * relief, so the row reads like a bank of keys with one held down.
 *
 * Segments share the width equally while every label fits on one line. When a
 * label is wider than its share (large font sizes, narrow screens) the row
 * keeps the labels whole and scrolls sideways as one row instead of clipping.
 */
@Composable
fun Segmented(
    segments: List<Segment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "segmented",
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().testTag(tag)) {
        val share = maxWidth / segments.size.coerceAtLeast(1)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            segments.forEachIndexed { index, segment ->
                SegmentKey(
                    segment = segment,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier.widthIn(min = share).testTag("$tag-$index"),
                )
            }
        }
    }
}

@Composable
private fun SegmentKey(
    segment: Segment,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val face = if (selected) GridlineColors.active else GridlineColors.surface
    val text = if (selected) GridlineColors.textOnBlue else GridlineColors.textBody
    val relief = if (selected || pressed) GridlineRelief.reliefPressed else GridlineRelief.reliefRaised
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = GridlineMetrics.touchTarget)
            .background(face)
            .bevel(relief)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = GridlineSpacing.sp3, vertical = GridlineSpacing.sp2),
        horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (segment.running) {
            Box(Modifier.size(GridlineSpacing.sp2).background(GridlineColors.live).testTag("running"))
        }
        GlText(
            text = segment.label,
            style = GridlineType.uiStrong.copy(textAlign = TextAlign.Center),
            color = text,
            maxLines = 1,
        )
    }
}
