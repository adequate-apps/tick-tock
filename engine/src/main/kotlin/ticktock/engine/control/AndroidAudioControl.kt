// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import android.content.Context
import android.media.AudioManager
import ticktock.core.contracts.ForegroundOwner
import ticktock.core.contracts.NoForegroundOwner
import ticktock.engine.NativeBridge
import ticktock.engine.NativeCalls
import ticktock.engine.output.NativeDiagnostics
import ticktock.engine.output.NativeStreamBackend

/**
 * The output stack the app runs on a device: the control thread over the
 * native backend over the bridge, with the platform's audio manager for
 * output devices and the audio mode. One instance per process; the app
 * keeps it for its lifetime and calls [AudioControlThread.shutdown] on
 * [control] at the end.
 */
class AndroidAudioControl private constructor(
    val control: AudioControlThread,
    private val backend: NativeStreamBackend,
) {
    /** The native counters of the open stream for Diagnostics, or null while none is open. */
    fun nativeDiagnostics(): NativeDiagnostics? = backend.diagnostics()

    companion object {
        /**
         * [native] defaults to the loaded library; a harness passes a fake so
         * the whole stack runs on the JVM.
         */
        fun create(
            context: Context,
            native: NativeCalls = NativeBridge.load(),
            foregroundOwner: ForegroundOwner = NoForegroundOwner,
        ): AndroidAudioControl {
            val audioManager = context.getSystemService(AudioManager::class.java)
            val devices = AndroidOutputDevices(context.applicationContext, audioManager)
            val backend = NativeStreamBackend(native, devices::outputs)
            val control = AudioControlThread(backend, devices, AndroidAudioModes(audioManager), foregroundOwner)
            control.start()
            return AndroidAudioControl(control, backend)
        }
    }
}
