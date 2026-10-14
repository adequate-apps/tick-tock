// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import androidx.compose.runtime.Composable

/**
 * A release build has no debug readout: the symbol exists so the Tune pane
 * compiles the same in both build types, and it draws nothing.
 */
@Composable
fun DebugTuneExtras() = Unit
