// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.json.Json
import ticktock.core.music.Pitch
import ticktock.core.music.Transposition
import ticktock.core.ranges.BaseRanges
import ticktock.core.ranges.OverlayRanges
import ticktock.core.ranges.RangeResolver
import java.io.File

/**
 * CHK-01 and CHK-02: every instrument has the two ensemble notes (concert Bb
 * and concert A) and a check set of two to four notes across its registers.
 * The golden file pins all 73 keys; a change to the data or to the octave
 * rule is a reviewed golden update, in the same way as the ranges.
 */
class TuningNotesTest {
    private val goldenFile = File("src/test/resources/catalogue/tuning-notes-golden.json")

    @Test
    fun `CHK-02 the clarinet set is open G, low E and the twelfth, written`() {
        val set = TuningNotes.forInstrument(Catalogue.get("clar_bb"))
        assertEquals(listOf(67, 52, 71), set.check.map { it.writtenMidi })
        assertEquals(listOf(65, 50, 69), set.check.map { it.soundingMidi })
        // The same fingerings on every clarinet: the written notes are shared.
        for (instrument in Catalogue.byFamily.getValue(Family.CLARINET)) {
            assertEquals(instrument.key, listOf(67, 52, 71), TuningNotes.forInstrument(instrument).check.map { it.writtenMidi })
        }
    }

    @Test
    fun `CHK-02 every saxophone checks written F sharp, middle C sharp and fourth-line D`() {
        for (instrument in Catalogue.byFamily.getValue(Family.SAXOPHONE)) {
            val written = TuningNotes.forInstrument(instrument).check.map { it.writtenMidi }
            assertEquals(instrument.key, 3, written.size)
            assertEquals("${instrument.key}: F# first", 6, Math.floorMod(written[0], 12))
            assertEquals(instrument.key, listOf(73, 74), written.drop(1))
        }
        assertEquals(78, TuningNotes.forInstrument(Catalogue.get("eb_alto")).check[0].writtenMidi)
        // The baritone tunes the bottom-space F# (the Oregon worksheet).
        assertEquals(66, TuningNotes.forInstrument(Catalogue.get("eb_bari")).check[0].writtenMidi)
    }

    @Test
    fun `CHK-02 every key has two to four check notes inside its range, sounding inside the detector window`() {
        for (instrument in Catalogue.all) {
            val set = TuningNotes.forInstrument(instrument)
            val range = RangeResolver.resolve(instrument, BaseRanges.bundled, OverlayRanges.bundled)
            assertTrue("${instrument.key}: ${set.check.size} check notes", set.check.size in 2..4)
            assertEquals("${instrument.key}: duplicate check notes", set.check.size, set.check.map { it.writtenMidi }.toSet().size)
            for (note in set.check) {
                assertTrue("${instrument.key}: ${note.writtenMidi} outside ${range.extended}", note.writtenMidi in range.extended)
                assertEquals(instrument.key, Transposition.soundingFromWritten(note.writtenMidi, instrument.transposition), note.soundingMidi)
                assertTrue("${instrument.key}: ${note.soundingMidi} outside the window", note.soundingMidi in Pitch.DETECTOR_WINDOW)
                assertTrue("${instrument.key}: a check note needs a reason", note.reason.isNotBlank())
            }
        }
    }

    @Test
    fun `CHK-01 every key has concert Bb and concert A inside its keyed range, and the pads mark by pitch class`() {
        for (instrument in Catalogue.all) {
            val set = TuningNotes.forInstrument(instrument)
            val range = RangeResolver.resolve(instrument, BaseRanges.bundled, OverlayRanges.bundled)
            assertEquals(instrument.key, EnsembleNote.entries.toSet(), set.ensemble.keys)
            for ((ensemble, note) in set.ensemble) {
                assertEquals("${instrument.key} $ensemble", ensemble.concertPitchClass, Math.floorMod(note.soundingMidi, 12))
                assertTrue("${instrument.key} $ensemble: ${note.writtenMidi} outside keyed ${range.keyed}", note.writtenMidi in range.keyed)
                assertEquals(instrument.key, Transposition.soundingFromWritten(note.writtenMidi, instrument.transposition), note.soundingMidi)
            }
            assertEquals(instrument.key, set.ensemble.getValue(set.preferred), set.defaultDrone)
        }
        val trumpet = TuningNotes.forInstrument(Catalogue.get("trp_bb"))
        assertEquals(EnsembleNote.CONCERT_BB, trumpet.preferred)
        assertEquals(70, trumpet.ensemble.getValue(EnsembleNote.CONCERT_BB).soundingMidi)
        assertEquals(72, trumpet.ensemble.getValue(EnsembleNote.CONCERT_BB).writtenMidi)
        // Written C5 on a Bb trumpet is both the band's Bb and a check note.
        assertEquals(setOf(PadMark.ENSEMBLE, PadMark.CHECK), trumpet.marks(70))
        assertEquals(setOf(PadMark.ENSEMBLE), trumpet.marks(70 - 12))
        assertEquals(setOf(PadMark.ENSEMBLE), trumpet.marks(69 + 12))
        assertEquals(emptySet<PadMark>(), trumpet.marks(68))
        assertEquals(EnsembleNote.CONCERT_A, TuningNotes.forInstrument(Catalogue.get("violin")).preferred)
        assertEquals(69, TuningNotes.forInstrument(Catalogue.get("violin")).defaultDrone.soundingMidi)
    }

    @Test
    fun `CHK-03 next check walks the set in order and wraps`() {
        val set = TuningNotes.forInstrument(Catalogue.get("clar_bb"))
        val first = set.nextCheck(null)
        assertEquals(67, first.writtenMidi)
        assertEquals(52, set.nextCheck(first.soundingMidi).writtenMidi)
        assertEquals(71, set.nextCheck(50).writtenMidi)
        assertEquals(67, set.nextCheck(69).writtenMidi)
        // A drone on a note outside the set starts the set over.
        assertEquals(67, set.nextCheck(60).writtenMidi)
    }

    @Test
    fun `the ensemble octave follows the primary check note`() {
        // Tenor: the F# check sounds E4, so the band's Bb is Bb3, written C5.
        val tenor = TuningNotes.forInstrument(Catalogue.get("bb_tenor"))
        assertEquals(72, tenor.ensemble.getValue(EnsembleNote.CONCERT_BB).writtenMidi)
        // Tuba: low Bb, written as it sounds.
        val tuba = TuningNotes.forInstrument(Catalogue.get("tuba_bbb"))
        assertEquals(34, tuba.ensemble.getValue(EnsembleNote.CONCERT_BB).soundingMidi)
    }

    @Test
    fun `CHK-02 golden over all 73 keys`() {
        val actual = render()
        val actualFile = File("build/tuning-notes-golden.actual.json")
        actualFile.parentFile.mkdirs()
        actualFile.writeText(actual)
        assertTrue("golden file missing; review ${actualFile.path} and copy it to ${goldenFile.path}", goldenFile.isFile)
        val expected = goldenFile.readText()
        if (expected != actual) {
            throw AssertionError("tuning notes changed; review ${actualFile.path} against ${goldenFile.path} and copy it over if the change is intended")
        }
        assertEquals(Catalogue.all.size, Json.parse(expected).obj!!.keys.size)
    }

    private fun render(): String {
        val root = Json.obj()
        for (instrument in Catalogue.all.sortedBy { it.key }) {
            val set = TuningNotes.forInstrument(instrument)
            root[instrument.key] = Json.obj(
                "ensemble" to Json.obj(*EnsembleNote.entries.map { it.label to Json.str(label(set.ensemble.getValue(it))) }.toTypedArray()),
                "preferred" to Json.str(set.preferred.label),
                "check" to Json.arr(set.check.map { Json.str("${label(it)}: ${it.reason}") }),
            )
        }
        return Json.write(root)
    }

    private fun label(note: TuningNote) =
        "${Pitch.label(note.writtenMidi)} (${note.writtenMidi}) sounds ${Pitch.label(note.soundingMidi)} (${note.soundingMidi})"
}
