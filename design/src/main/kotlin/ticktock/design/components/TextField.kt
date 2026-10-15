// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import ticktock.design.GridlineColors
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.frame

/** A labelled, square entry field that preserves a draft until its owner saves it. */
@Composable
fun TextField(label: String, value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, multiline: Boolean = false, tag: String = "text-entry") {
    Column(modifier.fillMaxWidth().background(GridlineColors.field).frame().padding(GridlineSpacing.sp3)) {
        GlText(label, style = GridlineType.small, color = GridlineColors.textSecondary)
        BasicTextField(value, onValueChange,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = GridlineMetrics.touchTarget)
                .semantics { contentDescription = label }.testTag(tag),
            textStyle = GridlineType.value.copy(color = GridlineColors.textBody),
            cursorBrush = SolidColor(GridlineColors.active), singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            keyboardOptions = KeyboardOptions(imeAction = if (multiline) ImeAction.Default else ImeAction.Next))
    }
}
