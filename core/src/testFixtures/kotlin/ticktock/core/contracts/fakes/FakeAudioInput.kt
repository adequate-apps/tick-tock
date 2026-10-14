// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts.fakes

import ticktock.core.contracts.AudioInput
import ticktock.core.contracts.AudioSink
import ticktock.core.contracts.InputException
import ticktock.core.contracts.InputFailure
import ticktock.core.contracts.InputInfo
import ticktock.core.contracts.InputSource

/**
 * An [AudioInput] a test drives by hand. [push] delivers PCM to the sink the
 * way the capture thread would; [failNextStart] makes the next [start] report a
 * typed failure. [openCount] lets a test prove exactly one stream is open.
 */
class FakeAudioInput(
    var sampleRate: Int = 48_000,
    var deviceName: String = "Fake microphone",
) : AudioInput {
    private var sink: AudioSink? = null
    var failNextStart: InputFailure? = null

    /** Streams opened minus streams stopped; the contract requires 0 or 1. */
    var openCount: Int = 0
        private set
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set

    val isOpen: Boolean get() = sink != null

    override fun start(sink: AudioSink): Result<InputInfo> {
        startCalls++
        failNextStart?.let { failure ->
            failNextStart = null
            return Result.failure(InputException(failure))
        }
        // A restart replaces the stream (POL-17): close the old one first.
        if (this.sink != null) stop()
        this.sink = sink
        openCount++
        return Result.success(InputInfo(sampleRate, deviceName, InputSource.FAKE))
    }

    override fun stop() {
        stopCalls++
        if (sink != null) {
            sink = null
            openCount--
        }
    }

    /** Delivers [samples] as one block; returns false when nothing is listening. */
    fun push(samples: FloatArray, frames: Int = samples.size): Boolean {
        val target = sink ?: return false
        target(samples, frames)
        return true
    }
}
