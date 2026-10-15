// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.catalogue.Catalogue
import ticktock.core.music.Pitch
import ticktock.core.music.Transposition

/** Spec 5 on the pure counter. The alto's extended range is written 58..101, sounding 49..92. */
class StrayCounterTest {
    private val alto = Catalogue.get("eb_alto")

    private fun counter() = StrayCounter.forInstrument(alto)

    private fun reading(midi: Int, hop: Long = 0) = Reading(hop, midi, Pitch.hzFromMidi(midi, Pitch.A4_DEFAULT_HZ), 0.0, Pitch.A4_DEFAULT_HZ, 0)

    private fun feed(counter: StrayCounter, midi: Int, count: Int): Boolean {
        var hint = false
        repeat(count) { hint = counter.observe(reading(midi)) }
        return hint
    }

    @Test
    fun `the alto's extended range is what the counter judges against`() {
        val range = counter().range!!
        assertEquals(58, range.extended.low)
        assertEquals(101, range.extended.high)
        assertEquals(49, Transposition.soundingFromWritten(58, alto.transposition))
        assertEquals(92, Transposition.soundingFromWritten(101, alto.transposition))
    }

    @Test
    fun `HNT-01 three different notes outside the range bring the hint`() {
        val counter = counter()
        assertFalse(feed(counter, 40, 10))
        assertFalse(feed(counter, 42, 10))
        assertTrue(feed(counter, 44, 1))
        assertTrue(counter.hinting)
        assertEquals(setOf(40, 42, 44), counter.strays)

        // Strays above the range count the same way.
        val high = counter()
        assertFalse(feed(high, 95, 1))
        assertFalse(feed(high, 97, 1))
        assertTrue(feed(high, 99, 1))
    }

    @Test
    fun `HNT-02 a stray lives for 435 readings of any kind, and rests age nothing`() {
        val counter = counter()
        feed(counter, 40, 1)
        feed(counter, 42, 1)
        // 432 in-range readings bring the count to 434; the third stray is reading 435.
        feed(counter, 69, StrayCounter.FORGET_AFTER_READINGS - 3)
        assertTrue(feed(counter, 44, 1))

        val late = counter()
        feed(late, 40, 1)
        feed(late, 42, 1)
        // One reading more and the first stray has aged out before the third arrives.
        feed(late, 69, StrayCounter.FORGET_AFTER_READINGS - 2)
        assertFalse(feed(late, 44, 1))
        assertEquals(setOf(42, 44), late.strays)

        // A rest is the absence of readings: the counter has no clock to age by.
        val rested = counter()
        feed(rested, 40, 1)
        feed(rested, 42, 1)
        assertTrue(feed(rested, 44, 1))
    }

    @Test
    fun `HNT-03 one note, however played, never brings the hint`() {
        // One long held stray.
        assertFalse(feed(counter(), 40, 2000))

        // Repeated attacks on one stray note, with in-range playing between.
        val attacks = counter()
        repeat(20) {
            feed(attacks, 40, 5)
            feed(attacks, 69, 5)
        }
        assertFalse(attacks.hinting)

        // A single squeak.
        val squeak = counter()
        feed(squeak, 69, 50)
        assertFalse(feed(squeak, 100, 1))

        // Altissimo up to written F7 is inside the extended range.
        val altissimo = counter()
        val writtenF7 = Transposition.soundingFromWritten(101, alto.transposition)
        for (midi in writtenF7 - 6..writtenF7) feed(altissimo, midi, 5)
        assertFalse(altissimo.hinting)
        assertTrue(altissimo.strays.isEmpty())
    }

    @Test
    fun `HNT-04 strays spread 25 seconds apart never add up`() {
        val counter = counter()
        val rest = Hop.count(25.0)
        feed(counter, 40, 1)
        feed(counter, 69, rest)
        feed(counter, 42, 1)
        feed(counter, 69, rest)
        assertFalse(feed(counter, 44, 1))
        assertEquals(setOf(44), counter.strays)
    }

    @Test
    fun `HNT-07 an instrument change forgets the strays, and the catch-all never hints`() {
        val counter = counter()
        feed(counter, 40, 1)
        feed(counter, 42, 1)
        counter.instrumentChanged(Catalogue.get("bb_tenor"), StrayCounter.forInstrument(Catalogue.get("bb_tenor")).range)
        assertTrue(counter.strays.isEmpty())
        assertEquals("bb_tenor", counter.instrument.key)
        // Tenor extended written 58..101 sounds 44..87: 40 and 42 are strays again, but only two.
        feed(counter, 40, 1)
        assertFalse(feed(counter, 42, 1))

        val catchAll = StrayCounter.forInstrument(Catalogue.get(Catalogue.CATCH_ALL_KEY))
        assertNull(catchAll.range)
        for (midi in listOf(21, 30, 40, 100, 108)) feed(catchAll, midi, 3)
        assertFalse(catchAll.hinting)

        // An instrument with no saved range is judged against nothing.
        val unranged = StrayCounter(alto, range = null)
        for (midi in listOf(40, 42, 44, 46)) feed(unranged, midi, 1)
        assertFalse(unranged.hinting)
    }

    @Test
    fun `HNT-08 the hint is an observation, the readings keep flowing with their cents`() {
        val counter = counter()
        val drive = Drive(Detector(ResponseMode.NORMAL))
        for (midi in listOf(40, 42, 44)) drive.note(midi, 20)
        val hints = drive.saved.map { counter.observe(it) }
        assertTrue(hints.last())
        // Every saved reading still has its note, Hz and cents; the counter changed none of them.
        val afterHint = drive.saved.drop(hints.indexOfFirst { it })
        assertTrue(afterHint.isNotEmpty())
        assertTrue(afterHint.all { it.hz > 0.0 && it.cents.isFinite() })
        drive.note(44, 20)
        assertEquals(44, drive.detector.shownNote)
        assertTrue(drive.decisions.last().display != null)
    }
}
