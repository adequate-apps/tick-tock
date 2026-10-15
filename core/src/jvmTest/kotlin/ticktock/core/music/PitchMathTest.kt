// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchMathTest {
    @Test
    fun `A4 is the anchor and the octave doubles`() {
        assertEquals(440.0, Pitch.hzFromMidi(69, Pitch.A4_DEFAULT_HZ), 1e-9)
        assertEquals(880.0, Pitch.hzFromMidi(81, Pitch.A4_DEFAULT_HZ), 1e-9)
        assertEquals(261.6256, Pitch.hzFromMidi(60, Pitch.A4_DEFAULT_HZ), 1e-4)
        assertEquals(27.5, Pitch.hzFromMidi(21, Pitch.A4_DEFAULT_HZ), 1e-9)
        assertEquals(4186.009, Pitch.hzFromMidi(108, Pitch.A4_DEFAULT_HZ), 1e-3)
        assertEquals(442.0, Pitch.hzFromMidi(69, 442.0), 1e-9)
    }

    @Test
    fun `nearest note and signed cents agree with the reference`() {
        assertEquals(69, Pitch.nearest(442.0, Pitch.A4_DEFAULT_HZ)!!.midi)
        assertEquals(7.85, Pitch.centsFrom(442.0, 69, Pitch.A4_DEFAULT_HZ), 0.01)
        assertEquals(-7.89, Pitch.centsFrom(438.0, 69, Pitch.A4_DEFAULT_HZ), 0.01)
        assertEquals(0.0, Pitch.centsFrom(442.0, 69, 442.0), 1e-9)
        // A quarter tone above A4 rounds up to Bb4 and reads as flat of it.
        val quarter = Pitch.hzFromMidi(69, Pitch.A4_DEFAULT_HZ) * Math.pow(2.0, 0.5 / 12.0)
        assertEquals(69.5, Pitch.midiFromHz(quarter, Pitch.A4_DEFAULT_HZ), 1e-9)
        assertEquals(70, Pitch.nearest(quarter, Pitch.A4_DEFAULT_HZ)!!.midi)
        assertEquals(-50.0, Pitch.centsFrom(quarter, 70, Pitch.A4_DEFAULT_HZ), 1e-6)
        val nearest = Pitch.nearest(quarter, Pitch.A4_DEFAULT_HZ)!!
        assertEquals(70, nearest.midi)
        assertEquals(-50.0, nearest.cents, 1e-6)
        // No reading, no note.
        assertNull(Pitch.nearest(0.0, Pitch.A4_DEFAULT_HZ))
        assertNull(Pitch.nearest(Double.NaN, Pitch.A4_DEFAULT_HZ))
        assertNull(Pitch.nearest(Double.POSITIVE_INFINITY, Pitch.A4_DEFAULT_HZ))
        assertTrue(Pitch.midiFromHz(-1.0, Pitch.A4_DEFAULT_HZ).isNaN())
    }

    @Test
    fun `A4W-01 the reference ranges 415 to 466 with 440 as the default`() {
        assertEquals(415.0, Pitch.A4_HZ.start, 0.0)
        assertEquals(466.0, Pitch.A4_HZ.endInclusive, 0.0)
        assertEquals(440.0, Pitch.A4_DEFAULT_HZ, 0.0)
        // Every reference in the range keeps A4 within a semitone of 440, so
        // the note name of a tone never depends on the stepper.
        assertEquals(69, Pitch.nearest(415.0, 415.0)!!.midi)
        assertEquals(69, Pitch.nearest(466.0, 466.0)!!.midi)
        assertEquals(0.0, Pitch.centsFrom(415.0, 69, 415.0), 1e-9)
    }
}
