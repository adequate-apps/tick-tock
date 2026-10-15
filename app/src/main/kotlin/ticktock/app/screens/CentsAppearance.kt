// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import ticktock.app.R
import ticktock.core.music.Cents
import ticktock.core.music.CentsBand
import ticktock.design.GridlineColors
import ticktock.design.components.GlIcon

internal data class CentsAppearance(val number: Color, val ink: Color, val word: String, val cue: GlIcon)

/** Tune and the table use the same rounded-value band and non-colour cue. */
@Composable
internal fun centsAppearance(cents: Int): CentsAppearance = when (CentsBand.of(cents)) {
    CentsBand.IN_TUNE -> CentsAppearance(GridlineColors.positive, GridlineColors.positive,
        stringResource(R.string.tune_in_tune), GlIcon.CHECK)
    CentsBand.CLOSE -> CentsAppearance(GridlineColors.attention, GridlineColors.attentionInk,
        stringResource(R.string.tune_close), if (Cents.sharp(cents)) GlIcon.CUE_SHARP else GlIcon.CUE_FLAT)
    CentsBand.OFF -> CentsAppearance(GridlineColors.danger, GridlineColors.danger,
        stringResource(R.string.tune_off), if (Cents.sharp(cents)) GlIcon.CUE_SHARP_2 else GlIcon.CUE_FLAT_2)
}
