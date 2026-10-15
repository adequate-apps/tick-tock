// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.metro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The gap-bar fields (GAP-01, GAP-02): N bars on, M bars off, as two integers
 * in the setup. The on/off rule is written once here so the beat row (which
 * outlines a silent bar) and the engine (which silences it) agree on which
 * bar is which.
 */
class MetroSetupTest {
    private val base = MetroSetup(
        bpm = 120,
        beatsPerBar = 4,
        beatUnit = 4,
        compoundPulse = false,
        accents = List(4) { Accent.NORMAL },
        subdivision = 1,
        click = ClickSound.WOOD,
        gain = 1f,
    )

    @Test
    fun `a setup without gap bars sounds every bar`() {
        assertEquals(1, base.barsOn)
        assertEquals(0, base.barsOff)
        assertEquals(1, base.cycleBars)
        (0 until 12).forEach { bar -> assertEquals("bar $bar", true, base.barSounds(bar)) }
    }

    @Test
    fun `three bars on and one off silence the fourth bar of every cycle`() {
        // GAP-01 counts bars from one: bars 1 to 3 sound, bar 4 is silent, bar 5 sounds.
        val gapped = base.copy(barsOn = 3, barsOff = 1)
        assertEquals(4, gapped.cycleBars)
        assertEquals(listOf(true, true, true, false, true, true, true, false), (0 until 8).map(gapped::barSounds))
    }

    @Test
    fun `gap bars survive a copy as plain data`() {
        val gapped = base.copy(barsOn = 2, barsOff = 2)
        assertEquals(gapped, gapped.copy())
        assertEquals(2, gapped.copy().barsOn)
        assertEquals(2, gapped.copy().barsOff)
    }

    @Test
    fun `at least one bar must sound and the off count cannot be negative`() {
        assertThrows(IllegalArgumentException::class.java) { base.copy(barsOn = 0) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(barsOff = -1) }
    }

    @Test
    fun `gap bars are capped so a cycle stays something a player can count`() {
        assertEquals(MetroSetup.GAP_BARS_MAX, base.copy(barsOn = MetroSetup.GAP_BARS_MAX).barsOn)
        assertEquals(MetroSetup.GAP_BARS_MAX, base.copy(barsOff = MetroSetup.GAP_BARS_MAX).barsOff)
        assertThrows(IllegalArgumentException::class.java) { base.copy(barsOn = MetroSetup.GAP_BARS_MAX + 1) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(barsOff = MetroSetup.GAP_BARS_MAX + 1) }
    }

    @Test
    fun `a negative bar index is refused rather than wrapped`() {
        assertThrows(IllegalArgumentException::class.java) { base.copy(barsOn = 3, barsOff = 1).barSounds(-1) }
    }

    @Test
    fun `a runtime calibration bound survives edits and refuses a negative count`() {
        assertEquals(0, base.beatLimit)
        assertEquals(0, base.generation)
        val calibration = base.copy(beatLimit = 8, generation = 29)
        assertEquals(8, calibration.copy(bpm = 100).beatLimit)
        assertEquals(29, calibration.copy(bpm = 100).generation)
        assertThrows(IllegalArgumentException::class.java) { base.copy(beatLimit = -1) }
    }
}
