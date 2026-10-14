// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import android.app.Activity
import android.content.Context
import ticktock.core.contracts.OutputPort
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound
import ticktock.core.metro.MetroSetup
import ticktock.engine.control.AndroidAudioControl
import ticktock.engine.output.NativeDiagnostics

/**
 * How a debug build decides what the output pane drives. Nothing here exists
 * in a release build, and the pane is opt-in per launch, so a plain launch
 * shows the Tune screen as a release does and the goldens hold.
 *
 * Launch extras:
 *  - `ticktock.debug.drone_midi` (int): a sine drone at that MIDI note;
 *  - `ticktock.debug.metro_bpm` (int): the metronome in 4/4 on the wood click.
 *
 * Either extra opens the pane; the port is the device's [AndroidAudioControl],
 * one per process. A test sets [override] and the extras reach its port instead.
 */
object OutputSeam {
    const val EXTRA_DRONE_MIDI = "ticktock.debug.drone_midi"
    const val EXTRA_METRO_BPM = "ticktock.debug.metro_bpm"
    const val DRONE_SLOT = 0
    const val DRONE_GAIN = 0.5f
    const val METRO_GAIN = 0.8f

    /** A port a test supplies; it wins over the device stack. */
    @Volatile
    var override: OutputPort? = null

    class Choice(
        val droneMidi: Int?,
        val metroBpm: Int?,
        val port: OutputPort,
        /** The native counters of the open stream, null on a port that has none. */
        val nativeDiagnostics: () -> NativeDiagnostics?,
    )

    fun choose(activity: Activity): Choice? {
        val intent = activity.intent ?: return null
        val droneMidi = if (intent.hasExtra(EXTRA_DRONE_MIDI)) intent.getIntExtra(EXTRA_DRONE_MIDI, 0) else null
        val metroBpm = if (intent.hasExtra(EXTRA_METRO_BPM)) intent.getIntExtra(EXTRA_METRO_BPM, 0) else null
        if (droneMidi == null && metroBpm == null) return null
        override?.let { fixed -> return Choice(droneMidi, metroBpm, fixed) { null } }
        val stack = shared(activity.applicationContext)
        return Choice(droneMidi, metroBpm, stack.control, stack::nativeDiagnostics)
    }

    /** The one setup the pane starts: a plain 4/4 on the wood click at [bpm]. */
    fun metroSetup(bpm: Int): MetroSetup = MetroSetup(
        bpm = bpm,
        beatsPerBar = 4,
        beatUnit = 4,
        compoundPulse = false,
        accents = listOf(Accent.ACCENT, Accent.NORMAL, Accent.NORMAL, Accent.NORMAL),
        subdivision = 1,
        click = ClickSound.WOOD,
        gain = METRO_GAIN,
    )

    private var stack: AndroidAudioControl? = null

    /** The control thread outlives the activity: one per process, created on first use. */
    @Synchronized
    private fun shared(context: Context): AndroidAudioControl =
        stack ?: AndroidAudioControl.create(context).also { stack = it }
}
