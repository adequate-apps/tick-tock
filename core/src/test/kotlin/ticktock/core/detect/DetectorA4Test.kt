// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.music.Pitch

/** Spec 2.5: readings know their reference, and a change starts a new generation. */
class DetectorA4Test {
    @Test
    fun `A4W-01 the generation refuses a reference outside 415 to 466 and starts at 440`() {
        val start = A4Generation()
        assertEquals(440.0, start.hz, 0.0)
        assertEquals(0, start.generation)
        assertEquals(415.0, start.changedTo(415.0).hz, 0.0)
        assertEquals(466.0, start.changedTo(466.0).hz, 0.0)
        assertThrows(IllegalArgumentException::class.java) { start.changedTo(414.0) }
        assertThrows(IllegalArgumentException::class.java) { start.changedTo(466.5) }
        assertThrows(IllegalArgumentException::class.java) { A4Generation(430.0, -1) }
    }

    @Test
    fun `A4W-01 a 415 Hz tone at A4 415 reads zero cents`() {
        val drive = Drive(Detector(a4 = A4Generation(415.0)))
        drive.tone(415.0, 20)
        val reading = drive.saved.last()
        assertEquals(69, reading.midiSounding)
        assertEquals(0.0, reading.cents, 1e-9)
        assertEquals(415.0, reading.a4Hz, 0.0)
    }

    @Test
    fun `POL-19 every reading carries the A4 and generation in force at its hop`() {
        val drive = Drive(Detector(a4 = A4Generation(442.0, 3)))
        drive.tone(442.0, 20)
        for (reading in drive.saved) {
            assertEquals(442.0, reading.a4Hz, 0.0)
            assertEquals(3, reading.a4Generation)
            assertEquals(0.0, reading.cents, 1e-9)
        }
        for (display in drive.decisions.mapNotNull { it.display }) {
            assertEquals(442.0, display.a4Hz, 0.0)
            assertEquals(3, display.a4Generation)
        }
        assertEquals(3, drive.attacks.single().a4Generation)
    }

    @Test
    fun `POL-20 a change discards the pending readings and the next reading carries the new generation`() {
        val drive = Drive(Detector())
        drive.tone(442.0, 30)
        assertEquals(2, drive.detector.pendingCount)
        val before = drive.saved.size
        val change = drive.hop

        drive.detector.setA4(442.0)
        assertEquals(0, drive.detector.pendingCount)
        assertNull(drive.detector.shownNote)
        assertEquals(A4Generation(442.0, 1), drive.detector.a4)

        drive.tone(442.0, 30)
        val after = drive.saved.drop(before)
        assertTrue(after.isNotEmpty())
        assertTrue("a generation-0 reading after the change", after.all { it.a4Generation == 1 && it.a4Hz == 442.0 })
        assertTrue("a pending reading from before the change was filed later", after.all { it.hop >= change })
        // The tone that read +7.85 against 440 reads 0 against 442.
        assertEquals(0.0, after.last().cents, 1e-9)
        assertEquals(Pitch.centsFrom(442.0, 69, 440.0), drive.saved[before - 1].cents, 1e-9)
    }

    @Test
    fun `POL-21 setting the same value is still a new generation`() {
        val drive = Drive(Detector())
        drive.tone(440.0, 30)
        drive.detector.setA4(440.0)
        assertEquals(A4Generation(440.0, 1), drive.detector.a4)
        assertNull(drive.detector.shownNote)
        assertEquals(0, drive.detector.pendingCount)
        drive.tone(440.0, 30)
        assertEquals(setOf(0, 1), drive.saved.map { it.a4Generation }.toSet())
        assertEquals(2, drive.attacks.size)
    }

    @Test
    fun `POL-22 the reading stream is partitioned at the change so runs never blend`() {
        val drive = Drive(Detector())
        drive.tone(441.0, 50)
        val change = drive.hop
        drive.detector.setA4(443.0)
        drive.tone(441.0, 50)

        val byGeneration = drive.saved.groupBy { it.a4Generation }
        assertEquals(setOf(0, 1), byGeneration.keys)
        assertTrue(byGeneration.getValue(0).all { it.hop < change && it.a4Hz == 440.0 })
        assertTrue(byGeneration.getValue(1).all { it.hop >= change && it.a4Hz == 443.0 })
        // The same Hz is graded against each run's own reference.
        assertEquals(Pitch.centsFrom(441.0, 69, 440.0), byGeneration.getValue(0).last().cents, 1e-9)
        assertEquals(Pitch.centsFrom(441.0, 69, 443.0), byGeneration.getValue(1).last().cents, 1e-9)
    }

    @Test
    fun `POL-23 stored Hz re-derives against any later reference, and the reading names its own`() {
        val drive = Drive(Detector())
        drive.tone(442.0, 20)
        val stored = drive.saved.last()
        assertEquals(440.0, stored.a4Hz, 0.0)
        assertEquals(7.85, stored.cents, 0.01)
        // The table may show it against 442 by re-deriving from Hz alone.
        assertEquals(0.0, Pitch.centsFrom(stored.hz, stored.midiSounding, 442.0), 1e-9)
        // Or carry the reference it was graded against; both facts are on the reading.
        assertEquals(stored.cents, Pitch.centsFrom(stored.hz, stored.midiSounding, stored.a4Hz), 1e-9)
    }
}
