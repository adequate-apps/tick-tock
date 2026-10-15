// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import android.content.Context
import android.os.Handler
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputPort
import ticktock.core.contracts.SoundingNotes
import ticktock.engine.control.AndroidAudioControl
import ticktock.engine.output.NativeDiagnostics

/** The app's output lifetime, with a command barrier for state acknowledgements. */
interface OutputConnection {
    val port: OutputPort
    val soundingNotes: SoundingNotes

    /** [applied] runs after the control thread has handled every command in this batch. */
    fun send(commands: List<OutputCommand>, applied: () -> Unit)
    fun background()
    fun foreground()
    fun close()
    fun diagnostics(): NativeDiagnostics? = null
}

/** Constructed only when the player starts a tool. */
class DeviceOutputConnection(context: Context) : OutputConnection {
    private val stack = AndroidAudioControl.create(context.applicationContext)
    private val handler = Handler(stack.control.looper)
    override val port: OutputPort get() = stack.control
    override val soundingNotes: SoundingNotes get() = stack.control.soundingNotes

    override fun send(commands: List<OutputCommand>, applied: () -> Unit) {
        commands.forEach(port::send)
        handler.post { applied() }
    }

    override fun background() = stack.control.appBackgrounded()
    override fun foreground() = stack.control.appForegrounded()
    override fun close() = stack.control.shutdown()
    override fun diagnostics(): NativeDiagnostics? = stack.nativeDiagnostics()
}
