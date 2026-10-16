// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.runtime.Composable

/** Release screens have no fault-injection state or entry point. */
internal object ScreenRenderProbe {
    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun before(screen: String) = Unit
}
