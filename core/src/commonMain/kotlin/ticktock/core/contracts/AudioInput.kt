// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

/**
 * Receives captured audio. [samples] holds interleaved mono float PCM in the
 * range -1..1; only the first [frames] entries are valid for this delivery.
 * The array may be reused by the producer after the call returns, so a sink
 * that keeps audio must copy it.
 */
typealias AudioSink = (samples: FloatArray, frames: Int) -> Unit

/** What the input actually opened, as opposed to what was asked for. */
data class InputInfo(
    /** Rate the device delivers. Hop and window lengths are derived from it. */
    val sampleRate: Int,
    /** Human-readable device name for status sentences ("Built-in microphone"). */
    val deviceName: String,
    /** The capture preset that succeeded, for the Diagnostics page. */
    val source: InputSource,
    /** Maximum captured frames still upstream of the sink, including an in-flight delivery. */
    val bufferFrames: Int = 0,
)

/** Capture presets, tried in this order until one opens. */
enum class InputSource { UNPROCESSED, VOICE_RECOGNITION, MIC, FAKE }

/** Why an input could not open or stopped delivering. */
sealed interface InputFailure {
    val deviceName: String

    /** Another app holds the microphone. */
    data class Busy(override val deviceName: String) : InputFailure

    /** The chosen device is unplugged or has vanished. */
    data class Disconnected(override val deviceName: String) : InputFailure

    /** The platform refused every preset; [refusedRates] lists what was asked for. */
    data class Refused(override val deviceName: String, val refusedRates: List<Int>) : InputFailure

    /** No permission to record. */
    data class Denied(override val deviceName: String) : InputFailure
}

/** Thrown through [Result] by [AudioInput.start] so callers see a typed reason. */
class InputException(val failure: InputFailure) : RuntimeException(failure.toString())

/**
 * The microphone contract. Exactly one implementation is open at any time
 * (IN-01). [start] returns the opened configuration or a typed failure, and
 * delivers audio to [sink] on the implementation's capture thread until [stop].
 * A second [start] without a [stop] replaces the stream (POL-17).
 */
interface AudioInput {
    fun start(sink: AudioSink): Result<InputInfo>
    fun stop()
}
