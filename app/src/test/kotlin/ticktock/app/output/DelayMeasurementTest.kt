// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import org.junit.Assert.*
import org.junit.Test
import ticktock.core.contracts.OutputEvent
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound

class DelayMeasurementTest {
    @Test
    fun `BTC-01 advance beat delivery preserves signed endpoint contacts and rejects repeats`() {
        for (offset in listOf(-500L, -100L, 200L, 500L)) {
            val measurement = DelayMeasurement(21)
            repeat(8) { assertTrue(measurement.beat(beat(it))) }
            repeat(8) { index ->
                val contact = beat(index).nanos + offset * 1_000_000L
                assertTrue("offset $offset click $index", measurement.tap(contact))
                assertFalse("repeat at offset $offset click $index", measurement.tap(contact + 10_000_000L))
            }
            assertEquals(offset.toInt(), measurement.medianMs())
        }
    }

    @Test
    fun `BTC-01 original contact time retains its click after the next beat arrives`() {
        val measurement = DelayMeasurement(21)
        assertTrue(measurement.beat(beat(0)))
        repeat(8) { index ->
            if (index < 7) assertTrue(measurement.beat(beat(index + 1)))
            assertTrue("contact for click $index", measurement.tap(beat(index).nanos + 200_000_000L))
        }
        assertEquals(8, measurement.tapsReceived)
        assertEquals(200, measurement.medianMs())
    }

    @Test
    fun `BTC-01 calibration has eight uniform 100 bpm clicks and an even-count median`() {
        val measurement = DelayMeasurement(21)
        val setup = measurement.setup()
        assertEquals(100, setup.bpm)
        assertEquals(8, setup.beatLimit)
        assertEquals(21, setup.generation)
        assertEquals(1, setup.subdivision)
        assertEquals(0, setup.barsOff)
        assertEquals(List(4) { Accent.NORMAL }, setup.accents)
        assertEquals(ClickSound.CLICK, setup.click)
        val offsets = listOf(220, 180, 500, 200, 190, 210, 100, 230)
        offsets.forEachIndexed { index, offset ->
            val event = beat(index)
            assertTrue(measurement.beat(event))
            assertTrue(measurement.tap(event.nanos + offset * 1_000_000L))
        }
        assertEquals(205, measurement.medianMs())
        assertEquals(8, measurement.tapsReceived)
        assertEquals(beat(7).nanos, measurement.lastClickNanos)
    }

    @Test
    fun `BTC-01 stale beats duplicate clicks and repeated taps cannot complete a missing sample`() {
        val measurement = DelayMeasurement(21)
        assertFalse(measurement.tap(0))
        assertFalse(measurement.beat(beat(0).copy(generation = 20)))
        assertFalse(measurement.beat(beat(0).copy(audible = false)))
        assertFalse(measurement.beat(beat(8)))
        repeat(8) { index ->
            val event = beat(index)
            assertTrue(measurement.beat(event))
            assertFalse(measurement.beat(event.copy(nanos = event.nanos + 1_000_000)))
            if (index != 3) {
                assertTrue(measurement.tap(event.nanos + 100_000_000L))
                assertFalse(measurement.tap(event.nanos + 400_000_000L))
            }
        }
        assertEquals(7, measurement.tapsReceived)
        assertNull(measurement.medianMs())
    }

    @Test
    fun `BTC-01 signed offsets stay in range and earlier beat delivery never moves the tap target back`() {
        val measurement = DelayMeasurement(21)
        assertTrue(measurement.beat(beat(1)))
        assertTrue(measurement.beat(beat(0)))
        assertEquals(1, measurement.latest)
        assertFalse(measurement.tap(beat(1).nanos + 501_000_000L, 1))
        assertFalse(measurement.tap(beat(1).nanos - 501_000_000L, 1))
        assertTrue(measurement.tap(beat(1).nanos - 100_000_000L, 1))
        repeat(8) { index ->
            if (index > 1) assertTrue(measurement.beat(beat(index)))
            if (index != 1) assertTrue(measurement.tap(beat(index).nanos - 100_000_000L, index))
        }
        assertEquals(-100, measurement.medianMs())
    }

    private fun beat(index: Int) = OutputEvent.Beat(
        index = index.toLong(), frame = index * 28_800L, nanos = 1_000_000_000L + index * 600_000_000L,
        beatInBar = index % 4, bar = (index / 4).toLong(), generation = 21,
        silentBar = false, audible = true,
    )
}
