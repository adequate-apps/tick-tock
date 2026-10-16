// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

/** Release screens have no fault-injection state or entry point. */
internal object ScreenRenderProbe {
    @Suppress("UNUSED_PARAMETER")
    fun before(screen: String) = Unit
}
