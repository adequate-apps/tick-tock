// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import ticktock.design.Bevel
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineRelief
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.bevel

/**
 * Which face a button shows. [Default] is a raised System Gray face for most
 * actions; [Primary] is Active Blue for the one action a pane is about
 * (Start, Save, Continue).
 */
enum class ButtonTone { Default, Primary }

/**
 * A raised Gridline button: square, one-unit bevel, label in the middle. The
 * bevel flips to pressed while the finger is down and the face stays put; that
 * flip is the only feedback. The touch target is at least 48 dp each way.
 *
 * The label is a verb phrase that says what happens ("Start metronome",
 * "Reset this note"); see docs/design/gridline-android.md.
 */
@Composable
fun Button(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Default,
    enabled: Boolean = true,
    tag: String = "button",
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val face = when {
        tone == ButtonTone.Primary -> GridlineColors.active
        else -> GridlineColors.surface
    }
    val text = when {
        !enabled -> GridlineColors.textDisabled
        tone == ButtonTone.Primary -> GridlineColors.textOnBlue
        else -> GridlineColors.textBody
    }
    // A blue face keeps its relief in blues so the bevel reads as light falling
    // on the same material, which is what makes it a button rather than a strip.
    val relief = when {
        tone == ButtonTone.Primary && pressed -> Bevel(GridlineColors.blueDeep, GridlineColors.blueHover)
        tone == ButtonTone.Primary -> Bevel(GridlineColors.blueHover, GridlineColors.blueDeep)
        pressed -> GridlineRelief.reliefPressed
        else -> GridlineRelief.reliefRaised
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = GridlineMetrics.touchTarget, minHeight = GridlineMetrics.touchTarget)
            .background(face)
            .bevel(relief)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = GridlineSpacing.sp4, vertical = GridlineSpacing.sp2)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        GlText(text = label, style = GridlineType.uiStrong.copy(textAlign = TextAlign.Center), color = text)
    }
}
