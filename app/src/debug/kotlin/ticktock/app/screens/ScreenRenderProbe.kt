// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

/** In-process test seam only; no intent, receiver or visible control can install a fault. */
internal object ScreenRenderProbe {
    var beforeRender: ((String) -> Unit)? = null

    fun before(screen: String) {
        beforeRender?.invoke(screen)
    }
}
