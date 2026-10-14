// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import ticktock.core.contracts.fakes.FakeClock
import ticktock.core.contracts.fakes.FakeHistorySink
import ticktock.core.contracts.fakes.FakeSoundingNotes
import ticktock.core.detect.Reading

/** Round trips for the three one-method contracts: SoundingNotes, Clock, HistorySink. */
class SmallContractsTest {
    @Test
    fun `sounding notes come back as a copy the caller may keep`() {
        val notes = FakeSoundingNotes(62, 69)
        val first = notes.current()
        assertArrayEquals(intArrayOf(62, 69), first)

        notes.notes = intArrayOf(50)
        assertArrayEquals(intArrayOf(62, 69), first)
        assertNotSame(notes.current(), notes.current())
    }

    @Test
    fun `sounding notes are empty while nothing plays`() {
        assertEquals(0, FakeSoundingNotes().current().size)
    }

    @Test
    fun `clock frames and nanos advance together at the sample rate`() {
        val clock = FakeClock(sampleRate = 48_000)
        clock.advanceFrames(48_000)
        assertEquals(48_000L, clock.nowFrames())
        assertEquals(FakeClock.NANOS_PER_SECOND, clock.nowNanos())

        clock.advanceNanos(5)
        assertEquals(48_000L, clock.nowFrames())
        assertEquals(FakeClock.NANOS_PER_SECOND + 5, clock.nowNanos())
    }

    @Test
    fun `history sink keeps valid readings and counts invalid ones`() {
        val sink = FakeHistorySink()
        val good = Reading(hop = 10, midiSounding = 69, hz = 440.0, cents = -3.2, a4Hz = 440.0, a4Generation = 1)
        val badHz = good.copy(hz = Double.NaN)
        val badCents = good.copy(cents = 51.0)
        val badMidi = good.copy(midiSounding = 128)

        listOf(good, badHz, badCents, badMidi).forEach(sink::accept)

        assertEquals(listOf(good), sink.accepted)
        assertEquals(3, sink.rejected)
    }

    @Test
    fun `a reading carries its A4 and generation unchanged`() {
        val reading = Reading(hop = 3, midiSounding = 60, hz = 262.5, cents = 3.3, a4Hz = 442.0, a4Generation = 7)
        assertEquals(reading, reading.copy())
        assertEquals(442.0, reading.a4Hz, 0.0)
        assertEquals(7, reading.a4Generation)
    }
}
