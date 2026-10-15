// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** WCAG 2.x contrast between two opaque colours, from 1 to 21. */
object Contrast {
    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun channel(v: Float): Double = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    /** Normal-size text (WCAG AA). */
    const val TEXT = 4.5

    /** Large text and the edges of controls (WCAG AA). */
    const val LARGE_OR_UI = 3.0
}
