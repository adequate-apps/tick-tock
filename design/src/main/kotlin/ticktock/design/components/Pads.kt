// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import ticktock.design.Bevel
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineRelief
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.Line
import ticktock.design.bevel
import ticktock.design.frame
import ticktock.design.selectionOutline

/** A reference note or beat with its permanent marks and a separate moving outline. */
data class Pad(
    val label: String,
    val secondary: String = "",
    val marks: List<String> = emptyList(),
    val description: String = label,
    val selected: Boolean = false,
    val outlined: Boolean = false,
    val dimmed: Boolean = false,
    val enabled: Boolean = true,
)

/** Whole labels decide the column count. A twelve-note grid can use four, three, two or one column. */
@Composable
fun PadGrid(pads: List<Pad>, onClick: (Int) -> Unit, modifier: Modifier = Modifier, maxColumns: Int = 4, labelStyle: TextStyle = GridlineType.measure, tag: String = "pads") {
    if (pads.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val showSecondary = pads.any { it.secondary.isNotEmpty() }
    val markRows = pads.maxOf { it.marks.size }
    BoxWithConstraints(modifier.fillMaxWidth().testTag(tag)) {
        val widest = with(density) {
            pads.maxOf { pad ->
                maxOf(measurer.measure(pad.label, labelStyle, maxLines = 1).size.width,
                    measurer.measure(pad.secondary, GridlineType.dense, maxLines = 1).size.width,
                    pad.marks.maxOfOrNull { measurer.measure(it, GridlineType.dense, maxLines = 1).size.width } ?: 0)
            }.toDp() + GridlineSpacing.sp2 * 2 + GridlineMetrics.line * 4
        }.coerceAtLeast(GridlineMetrics.touchTarget)
        val columns = (minOf(maxColumns, pads.size) downTo 1).first { count ->
            pads.size % count == 0 && widest * count + GridlineSpacing.sp1 * (count - 1) <= maxWidth || count == 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1)) {
            pads.indices.chunked(columns).forEach { indices ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp1)) {
                    indices.forEach { index ->
                        PadKey(pads[index], { onClick(index) }, showSecondary, markRows, labelStyle,
                            Modifier.weight(1f).fillMaxHeight(), "$tag-$index")
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(pad: Pad, onClick: () -> Unit, showSecondary: Boolean, markRows: Int, labelStyle: TextStyle, modifier: Modifier, tag: String) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val face = if (pad.selected) GridlineColors.selectionBg else GridlineColors.controlFace
    val ink = when {
        pad.selected -> GridlineColors.selectionFg
        pad.dimmed || !pad.enabled -> GridlineColors.textDisabled
        else -> GridlineColors.textBody
    }
    val relief = when {
        pad.selected -> Bevel(GridlineColors.blueDeep, GridlineColors.blueHover)
        pressed -> GridlineRelief.reliefPressed
        else -> GridlineRelief.reliefRaised
    }
    val edge = if (pad.outlined) GridlineMetrics.line * 2 else GridlineMetrics.line
    Column(
        modifier.defaultMinSize(minWidth = GridlineMetrics.touchTarget, minHeight = GridlineMetrics.touchTarget)
            .background(face).frame(Line(edge, if (pad.outlined) GridlineColors.textBody else if (pad.selected) GridlineColors.blueDeep else GridlineColors.border))
            .then(if (pad.selected) Modifier.selectionOutline() else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = pad.enabled, role = Role.Button, onClick = onClick)
            .semantics { selected = pad.selected; contentDescription = pad.description }
            .padding(edge).bevel(relief).padding(GridlineSpacing.sp2).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlText(pad.label, style = labelStyle.copy(textAlign = TextAlign.Center), color = ink)
        if (showSecondary) GlText(pad.secondary.ifEmpty { " " }, style = GridlineType.dense, color = ink)
        repeat(markRows) { row -> GlText(pad.marks.getOrElse(row) { " " }, style = GridlineType.dense, color = ink) }
    }
}
