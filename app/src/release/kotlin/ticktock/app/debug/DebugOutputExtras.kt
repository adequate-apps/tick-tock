// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import androidx.compose.runtime.Composable

/**
 * A release build has no debug output pane: the symbol exists so the Tune
 * screen compiles the same in both build types, and it draws nothing.
 */
@Composable
fun DebugOutputExtras() = Unit
