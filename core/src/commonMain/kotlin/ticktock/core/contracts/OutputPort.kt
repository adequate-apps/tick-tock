// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Route class of the device the output is actually playing through (OUT-12). */
enum class RouteClass {
    SPEAKER,
    /** Wired headphones, USB headsets, Bluetooth audio, LE audio and hearing aids. */
    HEADPHONES,
    UNKNOWN,
}

/** What the output stream opened with. Scheduling uses [sampleRate] and nothing else (OUT-14). */
data class OutputInfo(
    val sampleRate: Int,
    val framesPerBlock: Int,
    val routeClass: RouteClass,
    val deviceName: String,
    /** Measured output latency, for the visual beat offset. */
    val latencyMillis: Int,
)

/**
 * The one door from the app to the sound engine. Commands are fire-and-forget
 * and never block the caller (OUT-15); the engine answers through [events].
 * [info] is null while no stream is open.
 */
interface OutputPort {
    fun send(cmd: OutputCommand)
    val events: Flow<OutputEvent>
    val info: StateFlow<OutputInfo?>
}
