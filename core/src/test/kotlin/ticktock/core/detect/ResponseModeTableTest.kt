// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.InputSource

class ResponseModeTableTest {
    @Test
    fun `the three rows match the spec table`() {
        assertEquals(ModeParams(3, 2, 1, 0.15, 8e-5), ResponseModeTable.of(ResponseMode.FAST))
        assertEquals(ModeParams(5, 3, 2, 0.10, 1.5e-4), ResponseModeTable.of(ResponseMode.NORMAL))
        assertEquals(ModeParams(10, 6, 4, 0.07, 3e-4), ResponseModeTable.of(ResponseMode.SLOW))
    }

    @Test
    fun `first reading is confirm plus two edges in hops, about 186, 325 and 650 ms`() {
        assertEquals(4, ResponseModeTable.of(ResponseMode.FAST).firstReadingHops)
        assertEquals(7, ResponseModeTable.of(ResponseMode.NORMAL).firstReadingHops)
        assertEquals(14, ResponseModeTable.of(ResponseMode.SLOW).firstReadingHops)
        assertEquals(185.8, 4 * Hop.MILLIS, 0.1)
        assertEquals(325.1, 7 * Hop.MILLIS, 0.1)
        assertEquals(650.2, 14 * Hop.MILLIS, 0.1)
    }

    @Test
    fun `a mode must confirm on a majority of its window`() {
        assertThrows(IllegalArgumentException::class.java) { ModeParams(6, 3, 1, 0.1, 1e-4) }
    }

    @Test
    fun `POL-04 an unknown stored mode becomes Normal`() {
        assertEquals(ResponseMode.NORMAL, ResponseMode.fromStored("turbo"))
        assertEquals(ResponseMode.NORMAL, ResponseMode.fromStored(null))
        assertEquals(ResponseMode.NORMAL, ResponseMode.fromStored(""))
        assertEquals(ResponseMode.SLOW, ResponseMode.fromStored("slow"))
        assertEquals(ResponseMode.FAST, ResponseMode.fromWire("fast"))
        assertNull(ResponseMode.fromWire("Fast"))
        assertEquals(ResponseMode.NORMAL, ResponseMode.DEFAULT)
    }

    @Test
    fun `hop length follows the rate and stays 46 ms`() {
        assertEquals(2048, Hop.frames(44_100))
        assertEquals(2229, Hop.frames(48_000))
        assertEquals(4458, Hop.windowFrames(48_000))
        assertEquals(46.44, Hop.MILLIS, 0.01)
        assertEquals(215, Hop.count(10.0))
        assertEquals(43, Hop.count(2.0))
    }

    @Test
    fun `SRC-03 floors shift by the source offset so the same note gates the same way`() {
        val normal = ResponseModeTable.of(ResponseMode.NORMAL)
        val unprocessed = Gate.floor(normal, InputSource.UNPROCESSED)
        val voice = Gate.floor(normal, InputSource.VOICE_RECOGNITION)
        val mic = Gate.floor(normal, InputSource.MIC)
        val fake = Gate.floor(normal, InputSource.FAKE)
        assertEquals(1.5e-4, unprocessed, 0.0)
        assertEquals(1.5e-4, fake, 0.0)
        assertEquals(voice, mic, 0.0)
        // 18 dB is a factor of about 7.94 in amplitude.
        assertEquals(7.943, voice / unprocessed, 0.001)
        assertEquals(-18.0, SourceLevel.UNPROCESSED_RELATIVE_DB, 0.0)

        // One note at one sound pressure, just over the UNPROCESSED floor. The
        // processed presets deliver it 18 dB hotter; it passes under both.
        val spl = 2e-4
        val underVoice = spl * SourceLevel.linearFactor(InputSource.VOICE_RECOGNITION)
        val a = Detector(ResponseMode.NORMAL, InputSource.UNPROCESSED)
        val b = Detector(ResponseMode.NORMAL, InputSource.VOICE_RECOGNITION)
        assertEquals(HopClass.VOTE, a.step(HopEstimate(440.0, 0.01, spl), 0).hopClass)
        assertEquals(HopClass.VOTE, b.step(HopEstimate(440.0, 0.01, underVoice), 0).hopClass)
        // And a note under the floor is silence under both.
        val quiet = 1e-4
        assertEquals(RejectReason.SILENCE, a.step(HopEstimate(440.0, 0.01, quiet), 1).reject)
        assertEquals(RejectReason.SILENCE, b.step(HopEstimate(440.0, 0.01, quiet * SourceLevel.linearFactor(InputSource.VOICE_RECOGNITION)), 1).reject)
    }

    @Test
    fun `the player's noise floor raises the gate and shifts with the source too`() {
        val normal = ResponseModeTable.of(ResponseMode.NORMAL)
        // -40 dBFS is 0.01 linear, far above the mode floor.
        assertEquals(0.01, Gate.floor(normal, InputSource.UNPROCESSED, noiseFloorDb = -40), 1e-12)
        assertEquals(0.01 * SourceLevel.linearFactor(InputSource.MIC), Gate.floor(normal, InputSource.MIC, noiseFloorDb = -40), 1e-12)
        // -80 dBFS is 1e-4, below the mode floor, which then wins.
        assertEquals(1.5e-4, Gate.floor(normal, InputSource.UNPROCESSED, noiseFloorDb = -80), 1e-12)
        assertTrue(Gate.floor(normal, InputSource.UNPROCESSED, null) == normal.rmsFloor)
    }
}
