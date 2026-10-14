// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control.fakes

import android.os.Handler
import ticktock.engine.control.OutputDevices
import ticktock.engine.output.RoutedDevice

/** Devices a test plugs and unplugs; callbacks land on the handler the watcher gave, like the platform's. */
class FakeOutputDevices(initial: List<RoutedDevice>) : OutputDevices {
    private val present = initial.toMutableList()
    private var handler: Handler? = null
    private var listener: OutputDevices.Listener? = null
    var watching = false
        private set

    override fun outputs(): List<RoutedDevice> = synchronized(present) { present.toList() }

    override fun watch(handler: Handler, listener: OutputDevices.Listener) {
        this.handler = handler
        this.listener = listener
        watching = true
    }

    override fun unwatch() {
        watching = false
        listener = null
    }

    fun add(device: RoutedDevice) {
        synchronized(present) { present += device }
        deliver { it.onAdded(listOf(device)) }
    }

    fun remove(device: RoutedDevice) {
        synchronized(present) { present.remove(device) }
        deliver { it.onRemoved(listOf(device)) }
    }

    fun becomingNoisy() = deliver { it.onBecomingNoisy() }

    private fun deliver(call: (OutputDevices.Listener) -> Unit) {
        val target = listener ?: return
        checkNotNull(handler).post { call(target) }
    }
}
