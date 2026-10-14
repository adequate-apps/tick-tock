// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hz to MIDI and back against any A4, the arithmetic every reading and test shares. */
class PitchTest {
    @Test
    fun `A4 and its octaves against each reference`() {
        assertEquals(440.0, Pitch.hzFromMidi(69, 440.0), 1e-12)
        assertEquals(442.0, Pitch.hzFromMidi(69, 442.0), 1e-12)
        assertEquals(880.0, Pitch.hzFromMidi(81, 440.0), 1e-12)
        assertEquals(27.5, Pitch.hzFromMidi(21, 440.0), 1e-12)
        assertEquals(26.875, Pitch.hzFromMidi(21, 430.0), 1e-12)
        assertEquals(4186.009, Pitch.hzFromMidi(108, 440.0), 1e-3)
        assertEquals(261.6256, Pitch.hzFromMidi(60, 440.0), 1e-4)
        assertEquals(69.0, Pitch.midiFromHz(440.0, 440.0), 1e-12)
        assertEquals(69.5, Pitch.midiFromHz(Pitch.hzFromMidi(69.5, 440.0), 440.0), 1e-12)
        for (midi in Pitch.DETECTOR_WINDOW) {
            assertEquals(midi.toDouble(), Pitch.midiFromHz(Pitch.hzFromMidi(midi, 443.0), 443.0), 1e-9)
        }
    }

    @Test
    fun `nearest note and signed cents`() {
        val sharp = Pitch.nearest(445.0, 440.0)!!
        assertEquals(69, sharp.midi)
        assertEquals(19.56, sharp.cents, 0.01)
        val flat = Pitch.nearest(435.0, 440.0)!!
        assertEquals(69, flat.midi)
        assertEquals(-19.79, flat.cents, 0.01)
        assertEquals(NearestNote(69, 0.0), Pitch.nearest(442.0, 442.0))
        val quarterTone = Pitch.nearest(Pitch.hzFromMidi(69.5, 440.0), 440.0)!!
        assertEquals("an exact quarter tone rounds up", 70, quarterTone.midi)
        assertEquals(-50.0, quarterTone.cents, 1e-9)
        val justBelow = Pitch.nearest(Pitch.hzFromMidi(69.49, 440.0), 440.0)!!
        assertEquals(69, justBelow.midi)
        assertEquals(49.0, justBelow.cents, 1e-9)
        assertTrue(Pitch.nearest(1.0, 440.0)!!.midi < Pitch.DETECTOR_WINDOW.first)
    }

    @Test
    fun `no reading for a frequency that is not positive and finite`() {
        assertNull(Pitch.nearest(0.0, 440.0))
        assertNull(Pitch.nearest(-440.0, 440.0))
        assertNull(Pitch.nearest(Double.NaN, 440.0))
        assertNull(Pitch.nearest(Double.POSITIVE_INFINITY, 440.0))
        assertTrue(Pitch.midiFromHz(0.0, 440.0).isNaN())
    }
}
