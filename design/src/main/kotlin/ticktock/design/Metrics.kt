// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.unit.dp

/**
 * The two measurements Gridline needs on a phone that the token sheet does
 * not carry, because the sheet was written for a pointer and a screen.
 */
object GridlineMetrics {
    /** One unit of relief or frame. On a phone that is one density pixel. */
    val line = 1.dp

    /** The smallest touch target for any interactive control (A11Y-04). */
    val touchTarget = 48.dp
}
