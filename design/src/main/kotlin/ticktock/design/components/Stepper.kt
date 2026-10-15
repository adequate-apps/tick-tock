// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.frame
import ticktock.design.reliefInset

/**
 * A value with a key either side: − lowers it, + raises it. Each key is at
 * least 48 dp square and grows with large text; the value sits in a white inset field between them. The keys
 * carry [lowerLabel] and [raiseLabel] for TalkBack, since − and + alone say
 * nothing about what moves.
 */
@Composable
fun Stepper(
    value: String,
    onLower: () -> Unit,
    onRaise: () -> Unit,
    lowerLabel: String,
    raiseLabel: String,
    modifier: Modifier = Modifier,
    lowerEnabled: Boolean = true,
    raiseEnabled: Boolean = true,
    tag: String = "stepper",
) {
    Row(modifier.height(IntrinsicSize.Min).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Button(
            label = "−",
            onClick = onLower,
            enabled = lowerEnabled,
            modifier = Modifier.defaultMinSize(minWidth = GridlineMetrics.touchTarget).fillMaxHeight().semantics { contentDescription = lowerLabel },
            tag = "$tag-lower",
            labelPadding = GridlineSpacing.sp2,
            labelStyle = GridlineType.stepper,
        )
        Box(
            Modifier
                .weight(1f)
                .defaultMinSize(minHeight = GridlineMetrics.touchTarget)
                .fillMaxHeight()
                .background(GridlineColors.field)
                .frame()
                .padding(GridlineMetrics.line)
                .reliefInset()
                .padding(horizontal = GridlineSpacing.sp2),
            contentAlignment = Alignment.Center,
        ) {
            GlText(text = value, style = GridlineType.stepper, maxLines = 1, modifier = Modifier.testTag("$tag-value"))
        }
        Button(
            label = "+",
            onClick = onRaise,
            enabled = raiseEnabled,
            modifier = Modifier.defaultMinSize(minWidth = GridlineMetrics.touchTarget).fillMaxHeight().semantics { contentDescription = raiseLabel },
            tag = "$tag-raise",
            labelPadding = GridlineSpacing.sp2,
            labelStyle = GridlineType.stepper,
        )
    }
}
