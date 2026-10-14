// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import ticktock.core.contracts.AudioInput
import ticktock.engine.input.AndroidRecorderPlatform
import ticktock.engine.input.AudioRecordSource
import ticktock.engine.input.InputListener
import ticktock.engine.input.RecordingState

/**
 * How a debug build decides what feeds the live readout. Nothing here exists
 * in a release build, and the readout is opt-in per launch, so a plain launch
 * shows the Tune pane as a release does and the screenshot goldens hold.
 *
 * Launch extras:
 *  - `ticktock.debug.live` (boolean): the device microphone through [AudioRecordSource];
 *  - `ticktock.debug.tone_hz` (float): a synthesised tone through [ToneInput], with
 *    `ticktock.debug.tone_amp` (float, default 0.1) and `ticktock.debug.tone_rate` (int, default 48000).
 *
 * A test sets [override] and gets the readout without any extra.
 */
object InputSeam {
    const val EXTRA_LIVE = "ticktock.debug.live"
    const val EXTRA_TONE_HZ = "ticktock.debug.tone_hz"
    const val EXTRA_TONE_AMPLITUDE = "ticktock.debug.tone_amp"
    const val EXTRA_TONE_RATE = "ticktock.debug.tone_rate"
    const val DEFAULT_AMPLITUDE = 0.1f
    const val DEFAULT_RATE = 48_000

    /** An input a test supplies; it wins over the launch extras. */
    @Volatile
    var override: AudioInput? = null

    /** What the readout will listen to, and whether the microphone permission is needed first. */
    class Choice(
        val needsMicrophone: Boolean,
        private val build: (InputListener) -> AudioInput,
    ) {
        fun make(listener: InputListener): AudioInput = build(listener)
    }

    fun choose(activity: Activity): Choice? {
        override?.let { fixed -> return Choice(needsMicrophone = false) { fixed } }
        val intent = activity.intent ?: return null
        if (intent.hasExtra(EXTRA_TONE_HZ)) {
            val hz = intent.getFloatExtra(EXTRA_TONE_HZ, 0f).toDouble()
            val amplitude = intent.getFloatExtra(EXTRA_TONE_AMPLITUDE, DEFAULT_AMPLITUDE).toDouble()
            val rate = intent.getIntExtra(EXTRA_TONE_RATE, DEFAULT_RATE)
            return Choice(needsMicrophone = false) { ToneInput(hz, amplitude, rate) }
        }
        if (intent.getBooleanExtra(EXTRA_LIVE, false)) {
            val platform = AndroidRecorderPlatform(activity.applicationContext)
            return Choice(needsMicrophone = true) { listener -> AudioRecordSource(platform, listener = listener) }
        }
        return null
    }

    /** The platform's recording report, when the input is the real microphone (SRC-02). */
    fun recordingStateOf(input: AudioInput): StateFlow<RecordingState?>? = (input as? AudioRecordSource)?.recordingState
}
