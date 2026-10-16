// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.app.debug.InputSeam
import ticktock.app.output.TestOutputConnection
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.core.music.Pitch
import ticktock.data.history.NoteSummary
import ticktock.data.history.Scope

/** Actual PCM, actual picker, and two sounding output owners across the change. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class InstrumentSwitchAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private val input = FakeAudioInput(48_000)
    private val output = TestOutputConnection()
    private val tone = Sine(Pitch.hzFromMidi(72, 440.0), 0.1, 48_000)

    @After fun close() { scenario?.close(); InputSeam.override = null }

    private fun launch() {
        graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.output.makeConnection = { output }
        graph.prefs.update { it.copy(firstRunComplete = true, instrumentKey = "eb_alto",
            earsFirst = true, droneMidi = 57, metroBpm = 137) }
        InputSeam.override = input
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.runOnIdle { graph.output.startDrone(); graph.output.startMetro() }
        compose.waitUntil(5_000) { input.isOpen }
    }

    private fun feed(hops: Int, sounding: Boolean = true) {
        repeat(hops) {
            val before = graph.tune.level.value.hop
            assertTrue(input.push(if (sounding) tone.next(Hop.frames(48_000)) else FloatArray(Hop.frames(48_000))))
            compose.waitUntil(5_000) { graph.tune.level.value.hop > before }
        }
        compose.waitForIdle()
    }

    private fun switchToFlute() {
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("setting-instrument").performScrollTo().performClick()
        compose.onNodeWithTag("instrument-flute_c").performScrollTo().performClick()
        assertEquals("flute_c", graph.prefs.prefs.value.instrumentKey)
        compose.onNodeWithTag("back").performClick()
    }

    private fun commands() = output.sent.filterNot { it is OutputCommand.Duck }

    private fun assertHistoricalUnchanged(before: NoteSummary, after: NoteSummary) {
        assertEquals(before.label, after.label)
        assertEquals(before.midiSounding, after.midiSounding)
        assertEquals(before.n, after.n)
        assertEquals(before.attacks, after.attacks)
        assertEquals(before.meanCents, after.meanCents, 0.0)
        assertEquals(before.spreadCents, after.spreadCents, 0.0)
        assertEquals(before.meanAttackCents, after.meanAttackCents, 0.0)
        assertArrayEquals(before.bins, after.bins)
    }

    private fun assertAudioUnchanged(before: List<OutputCommand>) {
        assertEquals("instrument display/statistics change must not restart explicit tools", before, commands())
        assertEquals(setOf(OutputTool.DRONE, OutputTool.METRONOME), output.registered)
        assertTrue(graph.output.state.value.droneSounding && graph.output.state.value.metroSounding)
        assertEquals(57, graph.output.selectedMidi())
        assertEquals(57, graph.output.soundingDroneMidi)
        assertEquals(137, graph.prefs.prefs.value.metroBpm)
        assertTrue("no user microphone restart is needed", input.isOpen)
    }

    @Test fun `RNG-14 picker clears ended note and trace while retaining historical labels and sounding tools`() {
        launch()
        feed(24)
        feed(10, sounding = false)
        compose.waitUntil(5_000) { graph.tune.lastNote.value != null }
        assertTrue(graph.tune.trace.value.cents.any { it.isFinite() })
        compose.onNodeWithTag("last-note", useUnmergedTree = true).assertTextContains("A5", substring = true)
        assertTrue(graph.history.flushAndWait())
        val saved = runBlocking { graph.history.summaries(Scope.CurrentSession) }.single()
        assertTrue(saved.n > 0)
        assertEquals("eb_alto", saved.label.instrumentKey)
        assertEquals(-9, saved.label.transposition)
        val before = commands()

        switchToFlute()
        feed(1, sounding = false) // allow the analysis owner to consume the reset request
        assertNull("old last-note statistics cannot be respelled as the new instrument", graph.tune.lastNote.value)
        assertTrue("the prior instrument's trace is cleared", graph.tune.trace.value.cents.all { it.isNaN() })
        assertEquals(0, graph.tune.trace.value.attacks)
        compose.onNodeWithTag("last-note", useUnmergedTree = true).assertTextEquals("—")
        assertAudioUnchanged(before)
        assertHistoricalUnchanged(saved, runBlocking { graph.history.summaries(Scope.CurrentSession) }.single())
        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("drone-note", useUnmergedTree = true).assertTextEquals("A")
        compose.onNodeWithTag("drone-readout-octave", useUnmergedTree = true).assertTextEquals("3")
        compose.onNodeWithTag("tabs-3").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("table-note-72").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("table-note-72").assertContentDescriptionContains("A5", substring = true)
    }

    @Test fun `RNG-14 held PCM confirms afresh after picker change and both historical groups remain`() {
        launch()
        feed(24)
        assertEquals(72, graph.tune.state.value.readout.midi)
        assertTrue(graph.history.flushAndWait())
        val old = runBlocking { graph.history.summaries(Scope.CurrentSession) }.single()
        assertTrue(old.n > 0)
        assertEquals(1L, old.attacks)
        val before = commands()

        switchToFlute()
        feed(1)
        assertNull("held detector incumbent must confirm anew for the new instrument", graph.tune.state.value.readout.midi)
        assertNull("the instrument switch is not a completed-note reveal", graph.tune.lastNote.value)
        assertEquals(0, graph.tune.trace.value.attacks)
        assertTrue("old trace points cannot cross instruments", graph.tune.trace.value.cents.all { it.isNaN() })
        assertTrue(graph.history.flushAndWait())
        val afterFirstHop = runBlocking { graph.history.summaries(Scope.CurrentSession) }
        assertEquals("one fresh hop cannot become a stored continuation", 1, afterFirstHop.size)
        assertHistoricalUnchanged(old, afterFirstHop.single())

        feed(24)
        assertEquals(72, graph.tune.state.value.readout.midi)
        assertTrue(graph.history.flushAndWait())
        val rows = runBlocking { graph.history.summaries(Scope.CurrentSession) }
        assertEquals(2, rows.size)
        assertHistoricalUnchanged(old, rows.single { it.label.instrumentKey == "eb_alto" })
        val fresh = rows.single { it.label.instrumentKey == "flute_c" }
        assertEquals(0, fresh.label.transposition)
        assertEquals(72, fresh.midiSounding)
        assertTrue(fresh.n > 0)
        assertEquals("new instrument has its own confirmed attack", 1L, fresh.attacks)
        assertAudioUnchanged(before)
        compose.onNodeWithTag("tabs-3").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("table-note-72").fetchSemanticsNodes().size == 2 }
        compose.onNode(hasTestTag("table-note-72") and hasContentDescription("A5", substring = true)).assertExists()
        compose.onNode(hasTestTag("table-note-72") and hasContentDescription("C5", substring = true)).assertExists()
    }
}
