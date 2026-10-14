// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import ticktock.core.contracts.AudioInput
import ticktock.core.contracts.AudioSink
import ticktock.core.contracts.InputInfo
import ticktock.core.contracts.InputSource
import ticktock.core.detect.Hop
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.sin

/**
 * A debug-build [AudioInput] that delivers a known sine in real time, so the
 * whole path from capture to readout runs on an emulator that has no
 * microphone. Blocks are paced by the wall clock at a quarter hop each, the
 * way the real capture thread delivers, and the phase runs on across blocks
 * so the tone is one continuous signal.
 */
class ToneInput(
    val hz: Double,
    val amplitude: Double,
    val sampleRate: Int = 48_000,
) : AudioInput {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    override fun start(sink: AudioSink): Result<InputInfo> {
        stop()
        running = true
        thread = Thread({ loop(sink) }, "capture-tone").also { it.start() }
        return Result.success(InputInfo(sampleRate, DEVICE_NAME, InputSource.FAKE))
    }

    override fun stop() {
        running = false
        thread?.join()
        thread = null
    }

    private fun loop(sink: AudioSink) {
        val frames = Hop.frames(sampleRate) / BLOCKS_PER_HOP
        val block = FloatArray(frames)
        val step = 2.0 * PI * hz / sampleRate
        val blockNanos = TimeUnit.SECONDS.toNanos(1) * frames / sampleRate
        var phase = 0.0
        var due = System.nanoTime()
        while (running) {
            for (i in 0 until frames) {
                block[i] = (amplitude * sin(phase)).toFloat()
                phase += step
                if (phase >= 2.0 * PI) phase -= 2.0 * PI
            }
            sink(block, frames)
            due += blockNanos
            val wait = due - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
        }
    }

    companion object {
        const val DEVICE_NAME = "Synthesised tone"
        private const val BLOCKS_PER_HOP = 4
    }
}
