// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control.fakes

import android.media.AudioManager
import ticktock.engine.control.AudioModes
import java.util.concurrent.Executor

/** An audio mode a test sets; a registered listener hears the change on its executor, a poller reads it. */
class FakeAudioModes(private var mode: Int = AudioManager.MODE_NORMAL) : AudioModes {
    private var executor: Executor? = null
    private var listener: ((Int) -> Unit)? = null
    var reads = 0
        private set
    var listenCalls = 0
        private set
    val listening: Boolean get() = listener != null

    override fun current(): Int {
        reads++
        return mode
    }

    override fun listen(executor: Executor, listener: (mode: Int) -> Unit) {
        listenCalls++
        this.executor = executor
        this.listener = listener
    }

    override fun unlisten() {
        listener = null
        executor = null
    }

    /** The platform changes mode. With a listener in place it is told; a poller finds out on its next read. */
    fun setMode(next: Int) {
        mode = next
        val target = listener ?: return
        checkNotNull(executor).execute { target(next) }
    }
}
