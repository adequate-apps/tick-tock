// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.ranges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.catalogue.Catalogue

/** The two committed data files parse, carry their credit and version, and refuse bad shapes. */
class RangeLayerDataTest {
    @Test
    fun `bundled base layer parses with its credit and source hash`() {
        val base = BaseRanges.bundled
        assertEquals(1, base.version)
        assertEquals(64, base.sourceSha256.length)
        assertEquals(72, base.entries.size)
        val raw = BaseRanges::class.java.getResourceAsStream(BaseRanges.RESOURCE)!!.readBytes().decodeToString()
        assertTrue("base layer credits MuseScore", raw.contains("MuseScore"))
        assertTrue("base layer names its licence", raw.contains("GPL-3.0"))
        assertTrue("base layer names the generator", raw.contains("tools/gen-ranges/gen_ranges.py"))
        for ((key, entry) in base.entries) {
            assertTrue(key, entry.soundingKeyedLow <= entry.soundingKeyedHigh)
            assertTrue(key, entry.soundingExtendedLow <= entry.soundingExtendedHigh)
        }
    }

    @Test
    fun `bundled overlay parses and every entry has a reason`() {
        val overlay = OverlayRanges.bundled
        assertEquals(1, overlay.version)
        // RNG-03: the layer names the source document it was generated from, so a
        // regeneration can be checked against the same input.
        assertEquals(64, overlay.sourceSha256.length)
        assertTrue(overlay.sourceSha256.all { it in '0'..'9' || it in 'a'..'f' })
        val raw = BaseRanges::class.java.getResourceAsStream(OverlayRanges.RESOURCE)!!.readBytes().decodeToString()
        assertTrue("overlay names the generator", raw.contains("tools/gen-ranges/gen_overlay.py"))
        assertTrue(overlay.keyed.isNotEmpty())
        assertTrue(overlay.extended.isNotEmpty())
        (overlay.keyed.keys + overlay.extended.keys + overlay.decisions.keys).forEach { key ->
            assertTrue("overlay names unknown key $key", key in Catalogue.keys)
        }
        (overlay.keyed.values + overlay.extended.values).forEach { assertTrue(it.reason.isNotBlank()) }
    }

    @Test
    fun `base layer refuses another schema, an unknown version and a backwards span`() {
        assertThrows(RangeDataException::class.java) { BaseRanges.parse("""{"schema": "other", "version": 1, "instruments": {}}""") }
        assertThrows(RangeDataException::class.java) { BaseRanges.parse("""{"schema": "ticktock-range-base", "version": 2, "instruments": {}}""") }
        assertThrows(RangeDataException::class.java) {
            BaseRanges.parse(
                """{"schema": "ticktock-range-base", "version": 1, "instruments": {"x": {"musescoreId": "x", "transposeChromatic": 0, "soundingKeyed": [70, 60], "soundingExtended": [60, 70]}}}""",
            )
        }
    }

    @Test
    fun `overlay refuses a missing reason and an unknown version`() {
        assertThrows(RangeDataException::class.java) {
            OverlayRanges.parse("""{"schema": "ticktock-range-overlay", "version": 1, "keyed": {"oboe": {"low": 58, "high": 90, "reason": ""}}}""")
        }
        assertThrows(RangeDataException::class.java) {
            OverlayRanges.parse("""{"schema": "ticktock-range-overlay", "version": 1, "keyed": {"oboe": {"low": 58, "high": 90}}}""")
        }
        assertThrows(RangeDataException::class.java) { OverlayRanges.parse("""{"schema": "ticktock-range-overlay", "version": 9}""") }
        val minimal = OverlayRanges.parse("""{"schema": "ticktock-range-overlay", "version": 1}""")
        assertTrue(minimal.keyed.isEmpty() && minimal.extended.isEmpty() && minimal.decisions.isEmpty())
    }
}
