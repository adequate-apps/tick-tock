// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

/**
 * Why the engine parked every registered tool (OUT-02). There is no reason
 * for another app taking audio: owner decision 4 says the app coexists with
 * music players and never treats their playback as a reason to stop. An
 * incoming call is the one outside event that stops output.
 */
enum class ParkReason {
    HEADPHONES_DISCONNECTED,
    INCOMING_CALL,
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

    /**
     * Beat [index] reached stream [frame]; [nanos] is its monotonic presentation time.
     * [bar] and [silentBar] come from the generator, including a bar cut short by a metre edit.
     * [audible] describes the configured onset before master mute and gain.
     */
    data class Beat(
        val index: Long,
        val beatInBar: Int,
        val frame: Long,
        val nanos: Long,
        val bar: Long = 0,
        val silentBar: Boolean = false,
        val generation: Int = 0,
        val audible: Boolean = true,
    ) : OutputEvent

    /** Every registered tool is parked with [reason] until the player or a replug restarts it. */
    data class Parked(val reason: ParkReason, val tools: Set<OutputTool>) : OutputEvent

    /** The route came back; exactly [tools] restarted (OUT-05). */
    data class Resumed(val tools: Set<OutputTool>, val info: OutputInfo) : OutputEvent

    /** The device refused to open; the message names the device and the rates it refused (OUT-17). */
    data class Failed(val deviceName: String, val refusedRates: List<Int>) : OutputEvent

    /** Diagnostics counters since the stream opened (MET-04, POL-18). */
    data class Counters(val lateBeatsDropped: Int, val underruns: Int) : OutputEvent
}
