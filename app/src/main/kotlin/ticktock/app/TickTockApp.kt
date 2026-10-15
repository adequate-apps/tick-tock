// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.app.Application
import android.content.Context

/** Holds the [AppGraph]. It is built on first use, so a test that never touches storage starts no storage thread. */
class TickTockApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as TickTockApp).graph
    }
}
