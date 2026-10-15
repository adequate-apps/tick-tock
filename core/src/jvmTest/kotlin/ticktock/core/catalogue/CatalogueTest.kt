// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.json.Json
import ticktock.core.music.Pitch
import ticktock.core.music.Transposition
import ticktock.core.ranges.BaseRanges
import java.io.File

class CatalogueTest {
    @Test
    fun `73 keys, unique, every family populated, names unique`() {
        assertEquals(73, Catalogue.all.size)
        assertEquals(Catalogue.all.size, Catalogue.keys.size)
        assertEquals(Catalogue.all.size, Catalogue.all.map { it.displayName }.toSet().size)
        for (family in Family.entries) {
            assertTrue("family $family has no instruments", Catalogue.byFamily.getValue(family).isNotEmpty())
        }
        assertEquals(Catalogue.all, Catalogue.byFamily.values.flatten().sortedBy { Catalogue.all.indexOf(it) })
        assertEquals("bb_tenor", Catalogue.DEFAULT_KEY)
        assertTrue(Catalogue.get(Catalogue.CATCH_ALL_KEY).catchAll)
        assertEquals(1, Catalogue.all.count { it.catchAll })
    }

    @Test
    fun `catalogue ids match the generator's id map and the base layer`() {
        val idsFile = File("../tools/gen-ranges/instrument-ids.json")
        assertTrue("run from the :core module directory", idsFile.isFile)
        val fromFile = Json.parse(idsFile.readText()).obj!!.fields.mapValues { it.value.string!! }
        val fromCatalogue = Catalogue.all.filter { !it.catchAll }.associate { it.key to it.musescoreId!! }
        assertEquals(fromFile, fromCatalogue)
        assertEquals(fromFile.keys, BaseRanges.bundled.entries.keys)
        for ((key, entry) in BaseRanges.bundled.entries) assertEquals(key, fromFile[key], entry.musescoreId)
    }

    /**
     * TRN-04: one recorded octave-clef convention. Transposition is the chromatic
     * value MuseScore declares, and octave clefs change nothing, with exactly the
     * three documented decisions below. A new difference is a catalogue bug or a
     * new decision to write down here.
     */
    @Test
    fun `TRN-04 transposition follows the base layer except the recorded decisions`() {
        val decisions = mapOf(
            "rec_sopranino_f" to 12,
            "rec_soprano_c" to 12,
            "sousaphone_bbb" to 0,
        )
        val differences = Catalogue.all.filter { !it.catchAll }.mapNotNull { instrument ->
            val declared = BaseRanges.bundled.entries.getValue(instrument.key).transposeChromatic
            if (declared != instrument.transposition) instrument.key to instrument.transposition else null
        }.toMap()
        assertEquals(decisions, differences)
        assertEquals(0, Catalogue.get("guitar").transposition)
        assertEquals(0, Catalogue.get("banjo").transposition)
        assertEquals(0, Catalogue.get("rec_bass_f").transposition)
    }

    @Test
    fun `TRN-01 the four transpositions 1x had wrong`() {
        assertEquals(-9, Catalogue.get("horn_eb_alto").transposition)
        assertEquals(12, Catalogue.get("rec_sopranino_f").transposition)
        assertEquals(-12, Catalogue.get("double_bass").transposition)
        assertEquals(-12, Catalogue.get("bass_guitar").transposition)
    }

    @Test
    fun `TRN-02 sounding tones read as the fingered name`() {
        fun written(key: String, sounding: Int) = Pitch.label(Transposition.writtenFromSounding(sounding, Catalogue.get(key).transposition))
        assertEquals("E2", written("double_bass", 28)) // E1, 41.2 Hz
        assertEquals("E2", written("bass_guitar", 28))
        assertEquals("A4", written("horn_eb_alto", 60)) // C4
        assertEquals("F4", written("rec_sopranino_f", 77)) // F5
        assertEquals("C4", written("eb_contrabass", 27)) // D#1 sounding, written C4
        assertEquals("C3", written("contrabassoon", 36)) // C2 sounding, written C3
        assertEquals("C4", written("clar_contrabass_bb", 34)) // A#1 sounding, written C4
        assertEquals("C4", written("flute_bass_c", 48)) // C3 sounding, written C4
    }

    @Test
    fun `TRN-05 written equals sounding minus t for every key`() {
        for (instrument in Catalogue.all) {
            for (sounding in Pitch.DETECTOR_WINDOW) {
                val written = Transposition.writtenFromSounding(sounding, instrument.transposition)
                assertEquals(instrument.key, sounding - instrument.transposition, written)
                assertEquals(instrument.key, sounding, Transposition.soundingFromWritten(written, instrument.transposition))
            }
        }
    }

    @Test
    fun `RNG-09 bass-clef baritone and mellophone use their own MuseScore entries`() {
        assertEquals("baritone-horn", Catalogue.get("baritone_bc").musescoreId)
        assertEquals("mellophone", Catalogue.get("mellophone_f").musescoreId)
    }

    @Test
    fun `transposition text for the picker`() {
        assertEquals("sounds as written", Transposition.describe(0))
        assertEquals("sounds a major 2nd lower", Transposition.describe(-2))
        assertEquals("sounds an octave and major 2nd lower", Transposition.describe(-14))
        assertEquals("sounds an octave higher", Transposition.describe(12))
        assertEquals("sounds an octave lower", Transposition.describe(-12))
        assertEquals("sounds a perfect 4th higher", Transposition.describe(5))
        assertEquals("sounds 2 octaves and major 6th lower", Transposition.describe(-33))
        assertEquals("sounds a minor 3rd higher", Transposition.describe(3))
    }

    @Test
    fun `pitch labels`() {
        assertEquals("A4", Pitch.label(69))
        assertEquals("C4", Pitch.label(60))
        assertEquals("A#/Bb3", Pitch.label(58))
        assertEquals("A0", Pitch.label(21))
        assertEquals("C8", Pitch.label(108))
        assertEquals(21..108, Pitch.DETECTOR_WINDOW)
    }
}
