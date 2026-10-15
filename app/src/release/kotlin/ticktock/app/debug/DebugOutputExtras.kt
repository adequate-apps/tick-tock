// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import androidx.activity.ComponentActivity
import ticktock.app.AppGraph

/** Release builds take output requests only from the player's controls. */
fun installDebugOutput(activity: ComponentActivity, graph: AppGraph) = Unit
