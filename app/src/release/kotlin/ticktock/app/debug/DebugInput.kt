// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import android.app.Activity
import ticktock.app.AppGraph

/** A release build always listens to the microphone; the symbol exists so MainActivity compiles the same in both. */
@Suppress("UNUSED_PARAMETER")
fun installDebugInput(activity: Activity, graph: AppGraph) = Unit
