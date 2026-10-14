// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ticktock.core.catalogue.Catalogue
import ticktock.core.catalogue.Family

/** Note spelling follows the instrument unless the player overrides it (PLAN 2.1, features 9). */
class NoteSpellingTest {
    private val tenor = Catalogue.get("bb_tenor")
    private val violin = Catalogue.get("violin")

    @Test
    fun `wind and brass families spell in flats, strings voice and keyboard in sharps`() {
        val flats = setOf(
            Family.SAXOPHONE, Family.CLARINET, Family.FLUTE, Family.TRUMPET, Family.HORN,
            Family.TROMBONE, Family.LOW_BRASS, Family.DOUBLE_REED, Family.RECORDER,
        )
        for (instrument in Catalogue.all) {
            val expected = if (instrument.family in flats) Accidentals.FLATS else Accidentals.SHARPS
            assertEquals(instrument.key, expected, NoteSpelling.auto(instrument))
        }
        assertEquals(Accidentals.FLATS, NoteSpelling.auto(tenor))
        assertEquals(Accidentals.FLATS, NoteSpelling.auto(Catalogue.get("horn_f")))
        assertEquals(Accidentals.SHARPS, NoteSpelling.auto(violin))
        assertEquals(Accidentals.SHARPS, NoteSpelling.auto(Catalogue.get("guitar")))
        assertEquals(Accidentals.SHARPS, NoteSpelling.auto(Catalogue.get("piano")))
        assertEquals("the catch-all reads in sharps as 1.x did", Accidentals.SHARPS, NoteSpelling.auto(Catalogue.get(Catalogue.CATCH_ALL_KEY)))
    }

    @Test
    fun `an override replaces the automatic choice and null restores it`() {
        assertEquals(Accidentals.SHARPS, NoteSpelling.resolve(tenor, Accidentals.SHARPS))
        assertEquals(Accidentals.FLATS, NoteSpelling.resolve(violin, Accidentals.FLATS))
        assertEquals(Accidentals.FLATS, NoteSpelling.resolve(tenor, Accidentals.FLATS))
        for (instrument in Catalogue.all) {
            assertEquals(instrument.key, NoteSpelling.auto(instrument), NoteSpelling.resolve(instrument, null))
        }
    }

    @Test
    fun `black keys take the accidental, naturals read the same either way`() {
        assertEquals("A#4", NoteSpelling.name(70, Accidentals.SHARPS))
        assertEquals("Bb4", NoteSpelling.name(70, Accidentals.FLATS))
        assertEquals("A4", NoteSpelling.name(69, Accidentals.SHARPS))
        assertEquals("A4", NoteSpelling.name(69, Accidentals.FLATS))
        assertEquals("C#4", NoteSpelling.name(61, Accidentals.SHARPS))
        assertEquals("Db4", NoteSpelling.name(61, Accidentals.FLATS))
        assertEquals("F#", NoteSpelling.letter(66, Accidentals.SHARPS))
        assertEquals("Gb", NoteSpelling.letter(66, Accidentals.FLATS))
        assertEquals("Eb", NoteSpelling.letter(63, Accidentals.FLATS))
        assertEquals("G#", NoteSpelling.letter(68, Accidentals.SHARPS))
        assertEquals("Ab", NoteSpelling.letter(68, Accidentals.FLATS))
    }

    @Test
    fun `octave numbers follow C4 = 60 across the detector window`() {
        assertEquals("C4", NoteSpelling.name(60, Accidentals.FLATS))
        assertEquals("B3", NoteSpelling.name(59, Accidentals.SHARPS))
        assertEquals("A0", NoteSpelling.name(21, Accidentals.FLATS))
        assertEquals("Bb0", NoteSpelling.name(22, Accidentals.FLATS))
        assertEquals("C8", NoteSpelling.name(108, Accidentals.SHARPS))
        assertEquals("B7", NoteSpelling.name(107, Accidentals.FLATS))
        assertEquals(-1, Pitch.octave(0))
        assertEquals(4, Pitch.octave(60))
        assertEquals(8, Pitch.octave(108))
    }

    @Test
    fun `only the five black keys carry an accidental and the neutral label still agrees`() {
        for (midi in Pitch.DETECTOR_WINDOW) {
            val sharp = NoteSpelling.name(midi, Accidentals.SHARPS)
            val flat = NoteSpelling.name(midi, Accidentals.FLATS)
            val neutral = Pitch.label(midi)
            val octave = Pitch.octave(midi).toString()
            val letters = neutral.removeSuffix(octave)
            val black = letters.contains('/')
            assertEquals("accidental on $neutral", black, sharp.contains('#'))
            assertEquals("accidental on $neutral", black, flat.contains('b'))
            assertEquals((if (black) letters.substringBefore('/') else letters) + octave, sharp)
            assertEquals((if (black) letters.substringAfter('/') else letters) + octave, flat)
            for (name in listOf(sharp, flat)) {
                assertFalse(name, name.startsWith("Cb") || name.startsWith("B#") || name.startsWith("Fb") || name.startsWith("E#"))
            }
        }
    }
}
