// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import android.media.AudioManager
import android.os.Build
import android.os.Handler
import java.util.concurrent.Executor

/** The phases of a call that matter to output (decision 12). */
enum class CallState {
    NONE,

    /** The ringtone is sounding; output is muted and the tools keep running (CAL-01). */
    RINGING,

    /** In call, in communication or screening: every tool parks and nothing restarts on its own (CAL-02). */
    ANSWERED,
}

/**
 * The audio mode, which is how a call is detected without READ_PHONE_STATE
 * (CAL-03): Android moves the mode through RINGTONE and IN_CALL for the
 * dialer, IN_COMMUNICATION for VoIP apps and CALL_SCREENING for a screener.
 */
interface AudioModes {
    fun current(): Int

    /** Available from API 31; [listener] runs on [executor] on every change. */
    fun listen(executor: Executor, listener: (mode: Int) -> Unit)

    fun unlisten()
}

/** The platform implementation over [AudioManager]. */
class AndroidAudioModes(private val audioManager: AudioManager) : AudioModes {
    private var listener: AudioManager.OnModeChangedListener? = null

    override fun current(): Int = audioManager.mode

    override fun listen(executor: Executor, listener: (mode: Int) -> Unit) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { "the mode listener exists from API 31; poll below it" }
        unlisten()
        val modeListener = AudioManager.OnModeChangedListener { listener(it) }
        audioManager.addOnModeChangedListener(executor, modeListener)
        this.listener = modeListener
    }

    override fun unlisten() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listener?.let { audioManager.removeOnModeChangedListener(it) }
        listener = null
    }
}

/**
 * Watches the audio mode on the control thread and reports [CallState]
 * changes: through the mode-changed listener from API 31, by polling at 2 Hz
 * on API 28 to 30. Either way the ringing phase is seen within 500 ms.
 */
class CallWatcher(
    private val modes: AudioModes,
    private val handler: Handler,
    private val apiLevel: Int,
    private val onChange: (CallState) -> Unit,
) {
    var state: CallState = CallState.NONE
        private set

    private var polling = false
    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            observe(modes.current())
            handler.postDelayed(this, POLL_MILLIS)
        }
    }

    fun start() {
        observe(modes.current())
        if (apiLevel >= Build.VERSION_CODES.S) {
            modes.listen({ handler.post(it) }) { observe(it) }
        } else {
            polling = true
            handler.postDelayed(poll, POLL_MILLIS)
        }
    }

    fun stop() {
        polling = false
        handler.removeCallbacks(poll)
        modes.unlisten()
    }

    private fun observe(mode: Int) {
        val next = stateOf(mode)
        if (next == state) return
        state = next
        onChange(next)
    }

    companion object {
        /** 2 Hz: the bound on how late a ringtone is noticed on API 28 to 30. */
        const val POLL_MILLIS = 500L

        fun stateOf(mode: Int): CallState = when (mode) {
            AudioManager.MODE_RINGTONE -> CallState.RINGING
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
            -> CallState.ANSWERED
            else -> CallState.NONE
        }
    }
}
