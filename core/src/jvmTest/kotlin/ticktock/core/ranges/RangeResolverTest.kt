// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.ranges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.catalogue.Catalogue
import ticktock.core.music.Pitch

class RangeResolverTest {
    private val base = BaseRanges.bundled
    private val overlay = OverlayRanges.bundled

    private fun resolve(key: String, user: UserRangeOverride? = null) =
        RangeResolver.resolve(Catalogue.get(key), base, overlay, user)

    @Test
    fun `base ends come from MuseScore through the catalogue transposition`() {
        val r = resolve("flute_c")
        assertEquals(RangeEnd(60, RangeLayer.BASE), r.keyedLow)
        assertEquals(RangeEnd(93, RangeLayer.BASE), r.keyedHigh)
        assertEquals(RangeEnd(59, RangeLayer.BASE), r.extendedLow)
        assertEquals(RangeEnd(98, RangeLayer.BASE), r.extendedHigh)

        val bass = resolve("double_bass")
        assertEquals(WrittenRange(40, 74), bass.keyed)
        assertEquals(WrittenRange(36, 79), bass.extended)
    }

    @Test
    fun `app overlay keyed entry replaces keyed and reports its layer`() {
        val r = resolve("eb_alto")
        assertEquals(RangeEnd(58, RangeLayer.APP_OVERLAY), r.keyedLow)
        assertEquals(RangeEnd(90, RangeLayer.APP_OVERLAY), r.keyedHigh)
        assertEquals(RangeEnd(58, RangeLayer.BASE), r.extendedLow)
        assertEquals(RangeEnd(101, RangeLayer.BASE), r.extendedHigh)
    }

    @Test
    fun `app overlay keyed entry widens extended when it reaches past it`() {
        val r = resolve("eb_sopranino")
        assertEquals(RangeEnd(90, RangeLayer.APP_OVERLAY), r.keyedHigh)
        assertEquals(RangeEnd(90, RangeLayer.APP_OVERLAY), r.extendedHigh)
        // The base already starts at written 58, so the overlay's equal low moves nothing.
        assertEquals(RangeEnd(58, RangeLayer.BASE), r.extendedLow)
    }

    @Test
    fun `app overlay extended entry replaces extended`() {
        val r = resolve("tbn_contrabass")
        assertEquals(RangeEnd(21, RangeLayer.APP_OVERLAY), r.extendedLow)
        assertEquals(RangeEnd(72, RangeLayer.APP_OVERLAY), r.extendedHigh)
        assertEquals(RangeEnd(28, RangeLayer.BASE), r.keyedLow)

        val soprano = resolve("bb_soprano")
        assertEquals(RangeEnd(101, RangeLayer.APP_OVERLAY), soprano.extendedHigh)
    }

    @Test
    fun `RNG-15 user override replaces keyed and can only widen extended`() {
        val wider = resolve("eb_alto", UserRangeOverride(WrittenRange(55, 95)))
        assertEquals(RangeEnd(55, RangeLayer.USER), wider.keyedLow)
        assertEquals(RangeEnd(95, RangeLayer.USER), wider.keyedHigh)
        assertEquals(RangeEnd(55, RangeLayer.USER), wider.extendedLow)
        assertEquals(RangeEnd(101, RangeLayer.BASE), wider.extendedHigh)

        val narrower = resolve("eb_alto", UserRangeOverride(WrittenRange(60, 85)))
        assertEquals(WrittenRange(60, 85), narrower.keyed)
        assertEquals(WrittenRange(58, 101), narrower.extended)
        assertEquals(RangeLayer.BASE, narrower.extendedLow.setBy)

        val past = resolve("eb_alto", UserRangeOverride(WrittenRange(58, 104)))
        assertEquals(RangeEnd(104, RangeLayer.USER), past.extendedHigh)
    }

    @Test
    fun `RNG-10 the catch-all key spans the detector window`() {
        val r = resolve(Catalogue.CATCH_ALL_KEY)
        assertEquals(WrittenRange(48, 79), r.keyed)
        assertEquals(RangeLayer.APP_OVERLAY, r.keyedLow.setBy)
        assertEquals(WrittenRange(Pitch.DETECTOR_WINDOW.first, Pitch.DETECTOR_WINDOW.last), r.extended)
        assertEquals(RangeLayer.BASE, r.extendedLow.setBy)
        for (midi in Pitch.DETECTOR_WINDOW) {
            assertTrue(RangeResolver.inExtendedRange(Catalogue.get(Catalogue.CATCH_ALL_KEY), r, midi))
        }
    }

    @Test
    fun `in-range judgement uses the extended tier in sounding pitch`() {
        val alto = Catalogue.get("eb_alto")
        val r = resolve("eb_alto")
        assertTrue(RangeResolver.inExtendedRange(alto, r, 49)) // written Bb3 sounds Db3 = 49
        assertTrue(RangeResolver.inExtendedRange(alto, r, 92)) // written F7 sounds Ab6 = 92
        assertFalse(RangeResolver.inExtendedRange(alto, r, 48))
        assertFalse(RangeResolver.inExtendedRange(alto, r, 93))
    }

    @Test
    fun `resolution is pure`() {
        assertEquals(resolve("bb_tenor"), resolve("bb_tenor"))
        val user = UserRangeOverride(WrittenRange(50, 100))
        assertEquals(resolve("bb_tenor", user), resolve("bb_tenor", user))
    }

    @Test
    fun `a non catch-all key with no base entry is a data error`() {
        val empty = BaseRanges(1, "", emptyMap())
        assertThrows(RangeDataException::class.java) { RangeResolver.resolve(Catalogue.get("oboe"), empty, overlay) }
    }

    @Test
    fun `written range refuses to run backwards`() {
        assertThrows(IllegalArgumentException::class.java) { WrittenRange(70, 60) }
        assertEquals(WrittenRange(40, 90), WrittenRange(50, 60).span(WrittenRange(40, 90)))
        assertTrue(WrittenRange(40, 90).contains(WrittenRange(50, 60)))
        assertTrue(60 in WrittenRange(50, 60))
        assertFalse(61 in WrittenRange(50, 60))
    }
}
