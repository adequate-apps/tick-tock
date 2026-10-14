// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import ticktock.core.contracts.MuteReason
import java.util.EnumSet

/**
 * Everything that can silence output while the tools keep running. The two
 * contract reasons arrive as [ticktock.core.contracts.OutputCommand.Mute];
 * the ringing phase of a call is raised by the call watcher inside the engine
 * and never crosses the port (CAL-01), so it is an engine value rather than a
 * contract one.
 */
enum class MuteSource {
    RECORDING_ON_SPEAKER,
    BACKGROUND,
    CALL_RINGING,
    ;

    companion object {
        fun of(reason: MuteReason): MuteSource = when (reason) {
            MuteReason.RECORDING_ON_SPEAKER -> RECORDING_ON_SPEAKER
            MuteReason.BACKGROUND -> BACKGROUND
        }
    }
}

/**
 * The one owner of the set of mute reasons (OUT-10). Output is audible only
 * while the set is empty, so reasons that overlap in any order leave output
 * muted until every one of them has cleared. Only the control thread touches
 * an instance.
 */
class MuteReasons {
    private val active = EnumSet.noneOf(MuteSource::class.java)

    val muted: Boolean get() = active.isNotEmpty()

    /** A snapshot of the reasons in force. */
    val sources: Set<MuteSource> get() = if (active.isEmpty()) emptySet() else EnumSet.copyOf(active)

    /** Adds or clears [source]; returns whether [muted] changed as a result. */
    fun set(source: MuteSource, on: Boolean): Boolean {
        val before = muted
        if (on) active += source else active -= source
        return before != muted
    }
}
