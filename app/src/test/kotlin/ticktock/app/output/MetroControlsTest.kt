// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ticktock.core.contracts.OutputEvent
import ticktock.core.metro.Accent
import ticktock.core.prefs.Prefs

@OptIn(ExperimentalCoroutinesApi::class)
class MetroControlsTest {
    @Test
    fun `tap tempo accepts the slow endpoint and clamps small timing variation`() {
        val tap = TapTempo()
        assertNull(tap.tap(0))
        assertEquals(30, tap.tap(2_000_000_000))
        assertEquals(30, tap.tap(4_050_000_000))
        assertEquals(30, tap.tap(6_050_000_000))
    }

    @Test
    fun `tap tempo averages four intervals and resets after a three second pause`() {
        val tap = TapTempo()
        assertNull(tap.tap(0))
        assertEquals(120, tap.tap(500_000_000))
        assertEquals(120, tap.tap(1_000_000_000))
        assertEquals(120, tap.tap(1_500_000_000))
        assertEquals(120, tap.tap(2_000_000_000))
        assertEquals(96, tap.tap(3_000_000_000))
        assertNull(tap.tap(6_000_000_000))
        assertEquals(60, tap.tap(7_000_000_000))
    }

    @Test
    fun `GAP-04 each odd metre installs its grouping and custom metres retain their denominator`() {
        val starts = listOf(listOf(0, 3), listOf(0, 2, 4), listOf(0, 3, 5), listOf(0, 2, 4, 6))
        MetrePresets.all.drop(3).forEachIndexed { i, preset ->
            val p = preset.apply(Prefs(metroBpm = 137, metroSubdivision = 3, metroBarsOff = 2))
            assertEquals(starts[i], p.metroAccents.indices.filter { p.metroAccents[it] == Accent.ACCENT })
            assertEquals(137, p.metroBpm)
            assertEquals(3, p.metroSubdivision)
            assertEquals(2, p.metroBarsOff)
        }
        val custom = MetrePresets.custom(Prefs(), 12, 8)
        assertTrue(MetrePresets.hasCompoundPulse(custom))
        assertFalse(MetrePresets.hasCompoundPulse(custom.copy(metroBeatUnit = 4)))
        assertEquals(16, MetrePresets.custom(Prefs(), 40, 16).metroBeatsPerBar)
    }

    @Test
    fun `beat visuals use presentation time and signed offset with generation and cancellation`() = runTest {
        val visuals = BeatVisuals(backgroundScope) { testScheduler.currentTime * 1_000_000 }
        visuals.reset(2, 100)
        visuals.accept(OutputEvent.Beat(0, 0, 0, 200_000_000, bar = 7, silentBar = true, generation = 2))
        testScheduler.advanceTimeBy(299)
        testScheduler.runCurrent()
        assertNull(visuals.state.value)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(VisualBeat(0, 0, 7, true, true), visuals.state.value)
        visuals.accept(OutputEvent.Beat(1, 1, 0, 0, generation = 1))
        visuals.accept(OutputEvent.Beat(0, 3, 0, 0, generation = 2))
        testScheduler.runCurrent()
        assertEquals(0, visuals.state.value?.beat)
        visuals.accept(OutputEvent.Beat(2, 2, 0, 600_000_000, generation = 2))
        visuals.reset(3, -100)
        visuals.accept(OutputEvent.Beat(0, 0, 0, 500_000_000, generation = 3, audible = false))
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals(false, visuals.state.value?.audible)
        testScheduler.advanceTimeBy(1000)
        testScheduler.runCurrent()
        assertEquals(0L, visuals.state.value?.index)
    }
}
