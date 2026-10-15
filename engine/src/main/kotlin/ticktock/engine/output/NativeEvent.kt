// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason

/**
 * One event from the bridge, decoded from the numbers
 * [ticktock.engine.NativeBridge.Listener] receives. The kind is the
 * [OutputEvent] variant in declaration order, which the C++ side mirrors in
 * its `EventKind` enum; the field layout per kind is documented on the
 * listener and pinned by NativeEventTest.
 */
sealed interface NativeEvent {
    /** Opened or Resumed: the granted stream; [resumedTools] is empty for a fresh open. */
    data class Opened(
        val sampleRate: Int,
        val framesPerBurst: Int,
        val deviceId: Int,
        val latencyMillis: Int,
        val resumedTools: Set<OutputTool>,
    ) : NativeEvent

    data object Closed : NativeEvent

    data class Beat(val event: OutputEvent.Beat) : NativeEvent

    data class DuckSettled(val event: OutputEvent.DuckSettled) : NativeEvent

    data class Parked(val reason: ParkReason, val tools: Set<OutputTool>) : NativeEvent

    data class Failed(val deviceId: Int, val refusedRates: List<Int>) : NativeEvent

    data class Counters(val event: OutputEvent.Counters) : NativeEvent

    companion object {
        private const val KIND_OPENED = 0
        private const val KIND_CLOSED = 1
        private const val KIND_BEAT = 2
        private const val KIND_PARKED = 3
        private const val KIND_RESUMED = 4
        private const val KIND_FAILED = 5
        private const val KIND_COUNTERS = 6
        private const val KIND_DUCK_SETTLED = 7

        fun decode(kind: Int, a: Long, b: Long, c: Long, d: Long, tools: Int, ints: IntArray?): NativeEvent = when (kind) {
            KIND_OPENED -> Opened(a.toInt(), b.toInt(), c.toInt(), d.toInt(), emptySet())
            KIND_RESUMED -> Opened(a.toInt(), b.toInt(), c.toInt(), d.toInt(), toolSet(tools))
            KIND_CLOSED -> Closed
            KIND_DUCK_SETTLED -> DuckSettled(OutputEvent.DuckSettled(a.toInt(), c, d))
            KIND_BEAT -> {
                require(ints?.size == 4) { "native beat needs bar, flags and generation" }
                val bar = (ints[1].toLong() shl 32) or (ints[0].toLong() and 0xffffffffL)
                Beat(OutputEvent.Beat(
                    index = a, beatInBar = b.toInt(), frame = c, nanos = d,
                    bar = bar, silentBar = ints[2] and 2 != 0, generation = ints[3], audible = ints[2] and 1 != 0,
                ))
            }
            KIND_PARKED -> Parked(ParkReason.entries[a.toInt()], toolSet(tools))
            KIND_FAILED -> Failed(a.toInt(), ints?.toList().orEmpty())
            KIND_COUNTERS -> Counters(OutputEvent.Counters(lateBeatsDropped = a.toInt(), underruns = b.toInt()))
            else -> throw IllegalArgumentException("unknown native event kind $kind")
        }

        /** Bit i of the mask is the tool with ordinal i, as the native `OutputTool` enum numbers them. */
        private fun toolSet(mask: Int): Set<OutputTool> = OutputTool.entries.filterTo(linkedSetOf()) { mask and (1 shl it.ordinal) != 0 }
    }
}
