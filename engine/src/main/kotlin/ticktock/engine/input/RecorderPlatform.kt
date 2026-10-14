// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.input

import ticktock.core.contracts.InputSource

/** A microphone the platform lists. [name] is what status sentences say. */
data class InputDevice(
    val id: Int,
    val name: String,
    val isBuiltIn: Boolean,
)

/**
 * What the platform reports about a running capture (SRC-02, CAL-05). On
 * API 29 and later the recording configuration callback fills this; on API 28
 * the platform says nothing and [reported] stays false.
 */
data class RecordingState(
    /** The preset the platform says is on the path; null until it has reported. */
    val source: InputSource?,
    /** Names of the effects the platform applies on the path; empty when none. */
    val effects: List<String>,
    /** True while a call or a higher-priority capture silences this client. */
    val silenced: Boolean,
    val reported: Boolean,
)

/**
 * One opened capture stream. The production implementation wraps an
 * AudioRecord; tests script one. [read] blocks until [frames] are available
 * and returns the count read, 0 when the recorder is not recording, or a
 * negative error. [release] is the only way to free the platform resource and
 * must be called exactly once (IN-01).
 */
interface Recorder {
    val sampleRate: Int
    val source: InputSource
    val device: InputDevice

    /** False when the platform refuses to start, which is how a busy microphone shows before API 29. */
    fun start(): Boolean
    fun read(into: FloatArray, frames: Int): Int
    fun stop()
    fun release()

    /** Receives every recording configuration the platform reports; null removes the listener. */
    fun setRecordingListener(listener: ((RecordingState) -> Unit)?)
}

/** What one open attempt came to. */
sealed interface OpenResult {
    class Opened(val recorder: Recorder) : OpenResult

    /** The platform cannot open this preset at this rate; the chain moves on. */
    object Refused : OpenResult

    /** Recording is not permitted; the chain stops, since no other preset would be. */
    object Denied : OpenResult
}

fun interface DeviceListener {
    fun onDevicesChanged(added: List<InputDevice>, removed: List<InputDevice>)
}

/**
 * The platform behind [AudioRecordSource]: the property gate, the device
 * list, the open call and the device callback. The seam exists so the open
 * chain, the shared timeout and the capture loop are proven on the JVM
 * without a device, and so the real class holds nothing but AudioRecord calls.
 */
interface RecorderPlatform {
    /** True when the device reports the UNPROCESSED property (SRC-01). */
    fun supportsUnprocessed(): Boolean

    fun inputDevices(): List<InputDevice>

    /**
     * Opens a recorder on [device], or the platform default when null. May
     * block, which is why the caller runs it under a deadline (IN-06).
     */
    fun open(source: InputSource, sampleRate: Int, device: InputDevice?): OpenResult

    /** Registers for plug and unplug events; closing the handle unregisters. */
    fun watchDevices(listener: DeviceListener): AutoCloseable

    /** Raises the calling thread to the platform's capture priority; a no-op off the device. */
    fun promoteCaptureThread() {}
}
