// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.frame
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tap opens entry; a drag or an accessibility increment adjusts the same value. */
@Composable
fun NumberReadout(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    onStep: (Int) -> Unit,
    onEdit: () -> Unit,
    description: String,
    lowerLabel: String,
    raiseLabel: String,
    modifier: Modifier = Modifier,
    tag: String = "number",
) {
    val step by rememberUpdatedState(onStep)
    val pixelsPerStep = with(LocalDensity.current) { GridlineSpacing.sp2.toPx() }
    Box(
        modifier.defaultMinSize(minWidth = GridlineMetrics.touchTarget, minHeight = GridlineMetrics.touchTarget)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onEdit)
            .pointerInput(pixelsPerStep) {
                var remainder = 0f
                detectDragGestures(onDragStart = { remainder = 0f }) { change, movement ->
                    change.consume()
                    remainder += (if (abs(movement.x) >= abs(movement.y)) movement.x else -movement.y) / pixelsPerStep
                    val whole = remainder.toInt()
                    if (whole != 0) { step(whole); remainder -= whole }
                }
            }
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), range.last - range.first - 1)
                setProgress { onChange(it.roundToInt().coerceIn(range)); true }
                customActions = listOf(CustomAccessibilityAction(lowerLabel) { onStep(-1); true }, CustomAccessibilityAction(raiseLabel) { onStep(1); true })
            }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { GlText(value.toString(), style = GridlineType.note) }
}

/** The entry field keeps the draft until its owner accepts it, so typing a leading digit never clamps it early. */
@Composable
fun NumberField(value: String, onValueChange: (String) -> Unit, description: String, onDone: () -> Unit, modifier: Modifier = Modifier, tag: String = "number-entry") {
    val focus = remember { FocusRequester() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = GridlineMetrics.touchTarget)
            .background(GridlineColors.field).frame().padding(GridlineSpacing.sp3)
            .focusRequester(focus).semantics { contentDescription = description }.testTag(tag),
        textStyle = GridlineType.measure.copy(color = GridlineColors.textBody),
        cursorBrush = SolidColor(GridlineColors.active),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** The dialog uses the same title strip, relief and scrollable gray body as a pane. */
@Composable
fun SquareDialog(title: String, onDismiss: () -> Unit, state: String? = null, tag: String = "dialog", content: @Composable ColumnScope.() -> Unit) {
    val available = LocalConfiguration.current.screenHeightDp.dp - GridlineSpacing.sp8
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Pane(title, Modifier.padding(GridlineSpacing.sp2).widthIn(max = 560.dp).fillMaxWidth()
            .heightIn(max = available).verticalScroll(rememberScrollState()), state, tag, content)
    }
}
