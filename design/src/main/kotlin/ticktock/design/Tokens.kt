// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Gridline colour tokens. The values mirror the design system's token sheet
 * one for one; a generator that reproduces this file from the token CSS is
 * scheduled so the site and the app can never disagree about a colour.
 *
 * Green, amber and cyan carry meaning (in tune, close, live) and appear
 * nowhere else; everything structural is gray, white, ink and Active Blue.
 */
object GridlineColors {
    // Base
    val blue = Color(0xFF0000A8)
    val blueDeep = Color(0xFF000078)
    val blueHover = Color(0xFF1414B8)
    val gray = Color(0xFFC6C6C6)
    val grayLight = Color(0xFFE8E8E8)
    val grayLighter = Color(0xFFF2F2F2)
    val white = Color(0xFFFFFFFF)
    val ink = Color(0xFF101010)
    val rule = Color(0xFF666666)
    val ruleLight = Color(0xFF9A9A9A)
    val cyan = Color(0xFF008EA1)
    val green = Color(0xFF18743A)
    val yellow = Color(0xFFB87900)
    val yellowBg = Color(0xFFFFF4DC)
    val red = Color(0xFFB3261E)
    val redBg = Color(0xFFFBEAE9)

    // Relief
    val bevelLight = Color(0xFFFFFFFF)
    val bevelMid = Color(0xFFDFDFDF)
    val bevelDark = Color(0xFF8A8A8A)
    val bevelDarker = Color(0xFF4A4A4A)

    // Semantic
    val surface = gray
    val surface2 = grayLight
    val field = white
    val textBody = ink
    val textSecondary = Color(0xFF3D3D3D)
    val textDisabled = Color(0xFF7A7A7A)
    val textOnBlue = white
    val active = blue
    val selectionBg = blue
    val selectionFg = white
    val live = cyan
    val positive = green
    val attention = yellow
    val danger = red
    val borderStrong = ink
    val border = rule
    val borderLight = ruleLight
}

/** Spacing and fixed heights, in dp. Radius is always zero in Gridline. */
object GridlineSpacing {
    val sp1 = 4.dp
    val sp2 = 8.dp
    val sp3 = 12.dp
    val sp4 = 16.dp
    val sp5 = 20.dp
    val sp6 = 24.dp
    val sp8 = 32.dp

    val rowHeight = 26.dp
    val rowHeightDense = 24.dp
    val stripHeight = 32.dp
    val titleBarHeight = 30.dp
    val statusBarHeight = 24.dp
    val toolbarHeight = 34.dp

    /** One-unit relief and frame lines. */
    val line = 1.dp

    /** Smallest touch target for any control (A11Y-04). */
    val touchTarget = 48.dp
}
