// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

/** Why the engine parked every registered tool (OUT-02). */
enum class ParkReason {
    HEADPHONES_DISCONNECTED,
    FOCUS_LOST,
    OUTPUT_FAILED,
    BACKGROUND,
}

/**
 * What the engine reports back. Events are facts about what happened on the
 * audio thread, timestamped in frames of the open stream so the UI can map
 * them to wall time.
 */
sealed interface OutputEvent {
    data class Opened(val info: OutputInfo) : OutputEvent

    /** The stream closed because the registry went empty. */
    data object Closed : OutputEvent

    /** Beat [index] sounded at stream [frame]; [beatInBar] is zero-based. */
    data class Beat(val index: Long, val beatInBar: Int, val frame: Long, val nanos: Long) : OutputEvent

    /** Every registered tool is parked with [reason] until the player or a replug restarts it. */
    data class Parked(val reason: ParkReason, val tools: Set<OutputTool>) : OutputEvent

    /** The route came back; exactly [tools] restarted (OUT-05). */
    data class Resumed(val tools: Set<OutputTool>, val info: OutputInfo) : OutputEvent

    /** The device refused to open; the message names the device and the rates it refused (OUT-17). */
    data class Failed(val deviceName: String, val refusedRates: List<Int>) : OutputEvent

    /** Diagnostics counters since the stream opened (MET-04, POL-18). */
    data class Counters(val lateBeatsDropped: Int, val underruns: Int) : OutputEvent
}
