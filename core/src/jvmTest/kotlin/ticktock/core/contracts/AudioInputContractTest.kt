// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.fakes.FakeAudioInput

class AudioInputContractTest {
    @Test
    fun `audio pushed into the input reaches the sink unchanged`() {
        val input = FakeAudioInput(sampleRate = 44_100, deviceName = "USB interface", bufferFrames = 4_096)
        var received: FloatArray? = null
        var receivedFrames = -1

        val info = input.start { samples, frames ->
            received = samples.copyOf(frames)
            receivedFrames = frames
        }.getOrThrow()

        assertEquals(InputInfo(44_100, "USB interface", InputSource.FAKE, 4_096), info)
        val block = floatArrayOf(0.1f, -0.2f, 0.3f, 0f)
        assertTrue(input.push(block, frames = 3))
        assertArrayEquals(floatArrayOf(0.1f, -0.2f, 0.3f), received, 0f)
        assertEquals(3, receivedFrames)
    }

    @Test
    fun `stop closes the stream and later audio goes nowhere`() {
        val input = FakeAudioInput()
        var deliveries = 0
        input.start { _, _ -> deliveries++ }.getOrThrow()
        input.stop()

        assertFalse(input.push(FloatArray(8)))
        assertEquals(0, deliveries)
        assertEquals(0, input.openCount)
    }

    @Test
    fun `restart replaces the stream so exactly one is open`() {
        val input = FakeAudioInput()
        var first = 0
        var second = 0
        input.start { _, _ -> first++ }.getOrThrow()
        input.start { _, _ -> second++ }.getOrThrow()
        input.push(FloatArray(4))

        assertEquals(1, input.openCount)
        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test
    fun `a typed failure comes back through Result`() {
        val input = FakeAudioInput()
        input.failNextStart = InputFailure.Busy("Built-in microphone")

        val result = input.start { _, _ -> }

        val error = result.exceptionOrNull()
        assertTrue(error is InputException)
        assertEquals(InputFailure.Busy("Built-in microphone"), (error as InputException).failure)
        assertFalse(input.isOpen)
    }

    @Test
    fun `every failure names its device`() {
        val failures = listOf(
            InputFailure.Busy("A"),
            InputFailure.Disconnected("B"),
            InputFailure.Refused("C", listOf(48_000, 44_100)),
            InputFailure.Denied("D"),
        )
        assertEquals(listOf("A", "B", "C", "D"), failures.map { it.deviceName })
    }
}
