// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.LineHeightStyle

/**
 * IBM Plex, bundled from res/font so the app renders the same on every device
 * and offline. The weights are the ones the type scale uses and no more;
 * tools/fonts/MANIFEST.sha256 pins the files.
 */
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
 * The Gridline type scale, bound to the bundled families. Every size comes
 * from the generated [GridlineTypeScale]; numbers use the mono family with
 * tabular figures so a changing readout never jitters.
 */
object GridlineType {
    private val base = TextStyle(
        fontFamily = GridlineFonts.sans,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    private val mono = base.copy(fontFamily = GridlineFonts.mono, fontFeatureSettings = GridlineTypeScale.numeralFeature)

    val dense = base.copy(fontSize = GridlineTypeScale.fontSizeDense, lineHeight = GridlineTypeScale.lineHeightDense)
    val ui = base.copy(fontSize = GridlineTypeScale.fontSizeUi, lineHeight = GridlineTypeScale.lineHeightUi)
    val uiStrong = ui.copy(fontWeight = FontWeight.Medium)
    val caption = base.copy(
        fontSize = GridlineTypeScale.fontSizeCaption,
        lineHeight = GridlineTypeScale.lineHeightUi,
        fontWeight = GridlineTypeScale.captionWeight,
        letterSpacing = GridlineTypeScale.captionTracking,
    )
    val heading = base.copy(fontSize = GridlineTypeScale.fontSizeHeading, lineHeight = GridlineTypeScale.lineHeightUi, fontWeight = FontWeight.SemiBold)
    val title = base.copy(fontSize = GridlineTypeScale.fontSizeTitle, lineHeight = GridlineTypeScale.lineHeightUi, fontWeight = FontWeight.SemiBold)

    /** Large readouts: the note name, the tempo, the Hz. */
    val measure = mono.copy(fontSize = GridlineTypeScale.fontSizeMeasure, lineHeight = GridlineTypeScale.lineHeightUi, fontWeight = FontWeight.Medium)

    /** Small numeric facts beside a label: a value in a settings row, a count. */
    val value = mono.copy(fontSize = GridlineTypeScale.fontSizeUi, lineHeight = GridlineTypeScale.lineHeightUi)

    /** The readout's note name (SPEC 1: Sans 600 64 sp, tight tracking); line height equals the size. */
    val note = base.copy(fontSize = GridlineTypeScale.fontSizeNote, lineHeight = GridlineTypeScale.fontSizeNote, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)

    /** The octave beside the note name: Sans 500 at 28 sp. */
    val octave = base.copy(fontSize = GridlineTypeScale.fontSizeMeasure, lineHeight = GridlineTypeScale.fontSizeMeasure, fontWeight = FontWeight.Medium)

    /** The readout's cents: Mono 500 at 40 sp. */
    val cents = mono.copy(fontSize = GridlineTypeScale.fontSizeReadout, lineHeight = GridlineTypeScale.fontSizeReadout, fontWeight = FontWeight.Medium)

    /** A stepper's value: Mono 500 at 16 sp. */
    val stepper = mono.copy(fontSize = GridlineTypeScale.fontSizeStepper, lineHeight = GridlineTypeScale.lineHeightUi, fontWeight = FontWeight.Medium)

    /** Plot labels and legends: Mono 400 at 11 sp, the smallest size anywhere. */
    val small = mono.copy(fontSize = GridlineTypeScale.fontSizeSmall, lineHeight = GridlineTypeScale.lineHeightUi)

    val titleBar = base.copy(fontSize = GridlineTypeScale.fontSizeUi, lineHeight = GridlineTypeScale.lineHeightUi, fontWeight = FontWeight.SemiBold)
    val statusBar = mono.copy(fontSize = GridlineTypeScale.fontSizeDense, lineHeight = GridlineTypeScale.lineHeightUi)
}
