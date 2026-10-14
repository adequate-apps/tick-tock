// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ticktock.design.GridlineColors
import ticktock.design.GridlineType

/** Upper-case tracked label above a field or row group. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    GlText(text = text.uppercase(), style = GridlineType.caption, color = GridlineColors.textSecondary, modifier = modifier)
}
