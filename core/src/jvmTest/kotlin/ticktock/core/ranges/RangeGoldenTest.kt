// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.ranges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.catalogue.Catalogue
import ticktock.core.json.Json
import ticktock.core.music.Pitch
import ticktock.core.music.Transposition
import java.io.File

/**
 * RNG-04: every key's transposition, keyed range and extended range are pinned in
 * src/jvmTest/resources/ranges/golden.json, with the layer that set each end. A
 * change to either data layer or to the resolver shows up here as a reviewed
 * golden update: the test writes what it saw to build/ranges-golden.actual.json
 * and the message says where to copy it after reading the diff.
 */
class RangeGoldenTest {
    private val goldenFile = File("src/jvmTest/resources/ranges/golden.json")

    @Test
    fun `RNG-04 effective ranges match the golden file`() {
        val actual = render()
        val actualFile = File("build/ranges-golden.actual.json")
        actualFile.parentFile.mkdirs()
        actualFile.writeText(actual)
        assertTrue("golden file missing; review ${actualFile.path} and copy it to ${goldenFile.path}", goldenFile.isFile)
        val expected = goldenFile.readText()
        if (expected != actual) {
            val diff = describeDifferences(Json.parse(expected), Json.parse(actual))
            throw AssertionError("effective ranges changed:\n$diff\nReview ${actualFile.path} and copy it over ${goldenFile.path} if the change is intended.")
        }
    }

    @Test
    fun `RNG-01 keyed inside extended and sounding spans inside the detector window`() {
        for (instrument in Catalogue.all) {
            val r = RangeResolver.resolve(instrument, BaseRanges.bundled, OverlayRanges.bundled)
            assertTrue("${instrument.key}: keyed ${r.keyed} outside extended ${r.extended}", r.extended.contains(r.keyed))
            val low = Transposition.soundingFromWritten(r.extended.low, instrument.transposition)
            val high = Transposition.soundingFromWritten(r.extended.high, instrument.transposition)
            assertTrue("${instrument.key}: sounding $low..$high leaves ${Pitch.DETECTOR_WINDOW}", low >= Pitch.DETECTOR_WINDOW.first && high <= Pitch.DETECTOR_WINDOW.last)
        }
    }

    @Test
    fun `RNG-05 every wide professional top has a recorded decision`() {
        val wide = BaseRanges.bundled.entries.filter { (_, e) -> e.soundingExtendedHigh - e.soundingKeyedHigh >= 8 }.keys
        assertTrue("expected the policy to catch some keys", wide.isNotEmpty())
        val missing = wide.filter { it !in OverlayRanges.bundled.decisions }
        assertEquals("keys with professional top 8+ semitones above amateur top and no decision", emptyList<String>(), missing)
        val stale = OverlayRanges.bundled.decisions.keys.filter { it !in wide }
        assertEquals("decisions for keys the policy no longer selects", emptyList<String>(), stale)
    }

    @Test
    fun `RNG-06 the four main saxophones`() {
        for (key in listOf("bb_soprano", "eb_alto", "bb_tenor")) {
            val r = resolve(key)
            assertEquals(key, WrittenRange(58, 90), r.keyed)
            assertEquals(key, 101, r.extended.high)
        }
        val bari = resolve("eb_bari")
        assertEquals(WrittenRange(57, 90), bari.keyed)
        assertEquals(101, bari.extended.high)
    }

    @Test
    fun `RNG-07 the other saxophones`() {
        for (key in listOf("eb_sopranino", "bb_bass", "eb_contrabass")) assertEquals(key, 58, resolve(key).keyed.low)
        assertEquals(96, resolve("bb_bass").extended.high)
        // The owner's overlay keeps sopranino and contrabass at the keyed top until a maker's page confirms more.
        assertEquals(90, resolve("eb_sopranino").extended.high)
        assertEquals(90, resolve("eb_contrabass").extended.high)
    }

    @Test
    fun `RNG-08 corrected families reach the professional top`() {
        assertTrue(resolve("violin").extended.high >= 103) // G7
        assertTrue(resolve("cello").extended.high >= 90) // F#6
        assertEquals(WrittenRange(38, 84), resolve("horn_f").extended) // D2..C6
        for (key in listOf("flute_c", "oboe", "bassoon", "trp_bb", "tbn_tenor", "tbn_bass", "tuba_bbb", "tuba_cc", "tuba_eb", "tuba_f")) {
            val instrument = Catalogue.get(key)
            val professionalTop = Transposition.writtenFromSounding(BaseRanges.bundled.entries.getValue(key).soundingExtendedHigh, instrument.transposition)
            assertTrue(key, resolve(key).extended.high >= professionalTop)
        }
    }

    private fun resolve(key: String) = RangeResolver.resolve(Catalogue.get(key), BaseRanges.bundled, OverlayRanges.bundled)

    private fun render(): String {
        val root = Json.obj()
        for (instrument in Catalogue.all.sortedBy { it.key }) {
            val r = resolve(instrument.key)
            root[instrument.key] = Json.obj(
                "transposition" to Json.num(instrument.transposition),
                "keyed" to Json.str(label(r.keyed)),
                "keyedBy" to Json.str("${r.keyedLow.setBy.name.lowercase()}/${r.keyedHigh.setBy.name.lowercase()}"),
                "extended" to Json.str(label(r.extended)),
                "extendedBy" to Json.str("${r.extendedLow.setBy.name.lowercase()}/${r.extendedHigh.setBy.name.lowercase()}"),
            )
        }
        return Json.write(root)
    }

    private fun label(range: WrittenRange) = "${Pitch.label(range.low)}..${Pitch.label(range.high)} (${range.low}..${range.high})"

    private fun describeDifferences(expected: ticktock.core.json.JsonValue, actual: ticktock.core.json.JsonValue): String {
        val e = expected.obj!!.fields
        val a = actual.obj!!.fields
        val lines = ArrayList<String>()
        for (key in (e.keys + a.keys).sorted()) {
            val before = e[key]
            val after = a[key]
            if (before == after) continue
            lines.add("$key:\n  golden: ${before?.let { Json.write(it).trim().replace("\n", " ") } ?: "absent"}\n  actual: ${after?.let { Json.write(it).trim().replace("\n", " ") } ?: "absent"}")
        }
        return lines.joinToString("\n")
    }
}
