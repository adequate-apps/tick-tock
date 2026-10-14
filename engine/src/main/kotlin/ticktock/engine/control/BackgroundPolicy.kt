// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import ticktock.core.contracts.OutputTool

/**
 * The one documented background policy (OUT-11). It is asked once per
 * transition with every registered tool and answers with the tools that keep
 * sounding; the rest park with [ticktock.core.contracts.ParkReason.BACKGROUND].
 * Coming back to the foreground restarts nothing: the player's action does
 * (decision 4).
 */
fun interface BackgroundPolicy {
    fun keepsSounding(registered: Set<OutputTool>): Set<OutputTool>

    companion object {
        /**
         * 2.0: moving to the background stops everything. Android 17 silences
         * playback from a backgrounded app without an error unless a
         * foreground service carries it, and the service ships in v1.1 with
         * its media notification; until then a silent "running" metronome
         * would be a lie.
         */
        val StopEverything = BackgroundPolicy { emptySet() }
    }
}
