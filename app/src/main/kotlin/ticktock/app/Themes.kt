// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import ticktock.core.prefs.Theme
import ticktock.design.GridlinePalette
import ticktock.design.GridlineThemes

/** The palette for a stored theme choice; :design knows palettes and :core knows the choice. */
fun paletteOf(theme: Theme): GridlinePalette = when (theme) {
    Theme.GRIDLINE -> GridlineThemes.gridline
    Theme.DAYLIGHT -> GridlineThemes.daylight
    Theme.DARK -> GridlineThemes.dark
    Theme.PIT -> GridlineThemes.pit
}
