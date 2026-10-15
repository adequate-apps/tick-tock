// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.annotation.StringRes
import ticktock.app.R

/**
 * The four tool tabs. The order here is the order on screen; the index is
 * what navigation state and test tags use.
 */
enum class Tab(@StringRes val label: Int, val tag: String) {
    Tune(R.string.tab_tune, "tune"),
    Drone(R.string.tab_drone, "drone"),
    Metronome(R.string.tab_metronome, "metronome"),
    Table(R.string.tab_table, "table"),
}

/**
 * Where the frame is. Settings sits over the tabs rather than among them, so
 * the tab that was open is still open when the player comes back, and the
 * tools keep running underneath.
 */
sealed interface Screen {
    data class Tabs(val tab: Tab) : Screen
    data object Settings : Screen
}
