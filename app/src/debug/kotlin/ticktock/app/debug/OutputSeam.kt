// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import android.app.Activity

/** Debug launch arguments are consumed once; recreation cannot restart a tool the player stopped. */
object OutputSeam {
    const val EXTRA_DRONE_MIDI = "ticktock.debug.drone_midi"
    const val EXTRA_METRO_BPM = "ticktock.debug.metro_bpm"
    data class Choice(val droneMidi: Int?, val metroBpm: Int?, val a4Hz: Double?)

    fun choose(activity: Activity): Choice? {
        val intent = activity.intent ?: return null
        val drone = if (intent.hasExtra(EXTRA_DRONE_MIDI)) intent.getIntExtra(EXTRA_DRONE_MIDI, 69) else null
        val metro = if (intent.hasExtra(EXTRA_METRO_BPM)) intent.getIntExtra(EXTRA_METRO_BPM, 100) else null
        if (drone == null && metro == null) return null
        val a4 = if (intent.hasExtra(InputSeam.EXTRA_A4_HZ)) intent.getFloatExtra(InputSeam.EXTRA_A4_HZ, 440f).toDouble() else null
        intent.removeExtra(EXTRA_DRONE_MIDI)
        intent.removeExtra(EXTRA_METRO_BPM)
        return Choice(drone, metro, a4)
    }
}
