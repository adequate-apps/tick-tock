// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import org.junit.Assert.assertEquals
import org.junit.Test
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason

/**
 * The event field layout the bridge documents on NativeBridge.Listener,
 * decoded into typed values. The kind numbers are OutputEvent's declaration
 * order; NativeBridgeTest pins the C++ enum to the same order.
 */
class NativeEventTest {
    private fun decode(kind: Int, a: Long = 0, b: Long = 0, c: Long = 0, d: Long = 0, tools: Int = 0, ints: IntArray? = null) =
        NativeEvent.decode(kind, a, b, c, d, tools, ints)

    @Test
    fun `Opened and Resumed carry rate, burst, device and latency, and Resumed the tool mask`() {
        assertEquals(NativeEvent.Opened(48_000, 96, 7, 23, emptySet()), decode(0, 48_000, 96, 7, 23, tools = 0b0011))
        assertEquals(
            NativeEvent.Opened(44_100, 192, 9, 31, setOf(OutputTool.METRONOME, OutputTool.DRONE)),
            decode(4, 44_100, 192, 9, 31, tools = 0b0011),
        )
    }

    @Test
    fun `Closed has no fields`() {
        assertEquals(NativeEvent.Closed, decode(1, 5, 6, 7, 8, tools = 15))
    }

    @Test
    fun `Beat is index, beat in bar, frame and nanos`() {
        assertEquals(NativeEvent.Beat(OutputEvent.Beat(index = 41, beatInBar = 1, frame = 98_304, nanos = 1_234_567_890)), decode(2, 41, 1, 98_304, 1_234_567_890))
    }

    @Test
    fun `Parked is the reason ordinal and the tool mask with one bit per OutputTool ordinal`() {
        assertEquals(
            NativeEvent.Parked(ParkReason.HEADPHONES_DISCONNECTED, setOf(OutputTool.DRONE, OutputTool.TEST_TONE)),
            decode(3, 0, tools = 0b1010),
        )
        assertEquals(NativeEvent.Parked(ParkReason.OUTPUT_FAILED, emptySet()), decode(3, 2, tools = 0))
    }

    @Test
    fun `Failed is the device id and the refused rates, empty when the bridge sends none`() {
        assertEquals(NativeEvent.Failed(7, listOf(48_000, 44_100)), decode(5, 7, ints = intArrayOf(48_000, 44_100)))
        assertEquals(NativeEvent.Failed(7, emptyList()), decode(5, 7, ints = null))
    }

    @Test
    fun `Counters is late beats dropped and underruns`() {
        assertEquals(NativeEvent.Counters(OutputEvent.Counters(lateBeatsDropped = 3, underruns = 12)), decode(6, 3, 12))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown kind is refused rather than guessed`() {
        decode(7)
    }
}
