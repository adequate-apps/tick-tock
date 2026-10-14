// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

/**
 * The native controller's counters since its stream opened, decoded from
 * the array `nativeDiagnostics` returns in the order the bridge documents.
 * [framesRendered] is the render callback's own clock: a stream whose value
 * advances is being rendered, whatever the room says.
 */
data class NativeDiagnostics(
    val lateBeatsDropped: Long,
    val underruns: Long,
    val bufferBursts: Long,
    val growthSteps: Long,
    val droppedCommands: Long,
    val droppedBeats: Long,
    val framesRendered: Long,
    val sampleRate: Long,
    val streamOpen: Boolean,
    val parked: Boolean,
) {
    companion object {
        const val FIELDS = 10

        fun decode(values: LongArray): NativeDiagnostics {
            require(values.size == FIELDS) { "nativeDiagnostics returned ${values.size} values, expected $FIELDS" }
            return NativeDiagnostics(
                lateBeatsDropped = values[0],
                underruns = values[1],
                bufferBursts = values[2],
                growthSteps = values[3],
                droppedCommands = values[4],
                droppedBeats = values[5],
                framesRendered = values[6],
                sampleRate = values[7],
                streamOpen = values[8] != 0L,
                parked = values[9] != 0L,
            )
        }
    }
}
