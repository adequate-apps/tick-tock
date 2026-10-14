// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** IBM Plex, bundled so the app renders the same on every device and offline. */
object GridlineFonts {
    val sans = FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
        Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
    )
    val mono = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    )
}

/**
 * Gridline type scale. Sizes are in sp so system font scale applies; numbers
 * use the mono family with tabular figures so a changing readout never jitters.
 */
object GridlineType {
    private val base = TextStyle(
        fontFamily = GridlineFonts.sans,
        color = GridlineColors.textBody,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    val dense = base.copy(fontSize = 12.sp, lineHeight = 1.35.em)
    val ui = base.copy(fontSize = 13.sp, lineHeight = 1.4.em)
    val caption = base.copy(fontSize = 13.sp, lineHeight = 1.4.em, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em)
    val heading = base.copy(fontSize = 18.sp, lineHeight = 1.4.em, fontWeight = FontWeight.SemiBold)
    val title = base.copy(fontSize = 24.sp, lineHeight = 1.4.em, fontWeight = FontWeight.SemiBold)
    val measure = base.copy(fontSize = 28.sp, lineHeight = 1.4.em, fontFamily = GridlineFonts.mono, fontWeight = FontWeight.Medium)

    val titleBar = base.copy(fontSize = 13.sp, lineHeight = 1.4.em, fontWeight = FontWeight.SemiBold, color = GridlineColors.textOnBlue)
    val statusBar = base.copy(fontSize = 12.sp, lineHeight = 1.5.em, fontFamily = GridlineFonts.mono)
}
