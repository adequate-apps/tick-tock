// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import ticktock.engine.output.MuteSource
import ticktock.engine.output.StreamRequest

/**
 * What the Diagnostics page shows about the control thread: the buffer and
 * its growth steps (SHM-02), the mute set, the call state and a short log of
 * the things that happened to the stream (timed-out closes, route changes).
 */
data class ControlDiagnostics(
    val bufferBursts: Int = StreamRequest.INITIAL_BUFFER_BURSTS,
    val xruns: Int = 0,
    val muteSources: Set<MuteSource> = emptySet(),
    val callState: CallState = CallState.NONE,
    /** Most recent last; bounded so a day of unplugs cannot grow it. */
    val log: List<String> = emptyList(),
) {
    fun logged(line: String): ControlDiagnostics = copy(log = (log + line).takeLast(LOG_LINES))

    companion object {
        const val LOG_LINES = 32
    }
}
