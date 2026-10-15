// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.app.output.TestOutputConnection
import ticktock.app.screens.FrameCompositions
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class MetronomeScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private val link = TestOutputConnection()
    private fun launch(scale: Float = 1f) {
        RuntimeEnvironment.setFontScale(scale)
        graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.tune.micAllowed = { false }
        graph.output.makeConnection = { link }
        graph.prefs.update { it.copy(firstRunComplete = true) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("tabs-2").performClick()
    }
    @After fun close() { scenario?.close(); RuntimeEnvironment.setFontScale(1f) }
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test fun `click volume steps reach both exact endpoints`() {
        launch()
        compose.runOnIdle { graph.prefs.update { it.copy(clickGain = 0.5f) } }
        tap("metro-toggle")
        repeat(5) { tap("metro-volume-lower") }
        assertEquals(0f, graph.prefs.prefs.value.clickGain, 0f)
        compose.onNodeWithTag("metro-volume-lower").assertIsNotEnabled()
        assertEquals(0f, link.sent.filterIsInstance<OutputCommand.MetroUpdate>().last().setup.gain, 0f)
        repeat(10) { tap("metro-volume-raise") }
        assertEquals(1f, graph.prefs.prefs.value.clickGain, 0f)
        compose.onNodeWithTag("metro-volume-raise").assertIsNotEnabled()
    }

    @Test fun `OUT-02 parked and muted metronome actions preserve cancellation without claiming running`() {
        launch()
        tap("metro-toggle")
        compose.runOnIdle { link.park(ticktock.core.contracts.ParkReason.HEADPHONES_DISCONNECTED) }
        compose.onNodeWithTag("metro-toggle").performScrollTo().assertTextEquals("Cancel resume")
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Paused"))
        tap("metro-toggle")
        compose.runOnIdle { link.replug() }
        assertFalse(graph.output.state.value.metroWanted)
        tap("metro-toggle")
        compose.runOnIdle { link.controlDiagnostics.value = link.controlDiagnostics.value.copy(
            muteSources = setOf(ticktock.engine.output.MuteSource.CALL_RINGING)) }
        compose.onNodeWithTag("metro-toggle").assertTextEquals("Cancel resume")
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Muted"))
        tap("metro-toggle")
        assertFalse(graph.output.state.value.metroWanted)
    }

    @Test fun `MET-01 tempo entry steps drag and accessibility all change the same persisted value`() {
        launch()
        tap("metro-step-0"); assertEquals(95, graph.prefs.prefs.value.metroBpm)
        tap("metro-step-1"); assertEquals(94, graph.prefs.prefs.value.metroBpm)
        tap("metro-step-2"); assertEquals(95, graph.prefs.prefs.value.metroBpm)
        tap("metro-step-3"); assertEquals(100, graph.prefs.prefs.value.metroBpm)
        tap("metro-bpm")
        compose.onNodeWithTag("tempo-entry").performTextReplacement("29")
        compose.onNodeWithTag("tempo-use").assertIsNotEnabled()
        compose.onNodeWithTag("tempo-entry").performTextReplacement("137")
        tap("tempo-use")
        assertEquals(137, graph.prefs.prefs.value.metroBpm)
        val readout = compose.onNodeWithTag("metro-bpm").performScrollTo()
        readout.performSemanticsAction(SemanticsActions.SetProgress) { it(500f) }
        assertEquals(300, graph.prefs.prefs.value.metroBpm)
        readout.performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        readout.performTouchInput { swipe(center, center.copy(x = center.x + 80), durationMillis = 300) }
        assertTrue(graph.prefs.prefs.value.metroBpm > 100)
        assertTrue(link.sent.isEmpty())
    }

    @Test fun `MET-02 and GAP-04 metre accents subdivisions and click controls reach a running generator`() {
        launch()
        tap("metro-toggle")
        assertTrue(graph.output.state.value.metroSounding)
        tap("metro-metre-2")
        assertEquals(6, graph.prefs.prefs.value.metroBeatsPerBar)
        assertEquals(listOf(Accent.ACCENT, Accent.NORMAL, Accent.NORMAL, Accent.ACCENT, Accent.NORMAL, Accent.NORMAL), graph.prefs.prefs.value.metroAccents)
        tap("metro-pulse-1")
        assertTrue(graph.prefs.prefs.value.metroCompoundPulse)
        tap("metro-beats-0"); assertEquals(Accent.NORMAL, graph.prefs.prefs.value.metroAccents[0])
        tap("metro-beats-0"); assertEquals(Accent.SOFT, graph.prefs.prefs.value.metroAccents[0])
        tap("metro-beats-0"); assertEquals(Accent.MUTE, graph.prefs.prefs.value.metroAccents[0])
        tap("metro-beats-0"); assertEquals(Accent.ACCENT, graph.prefs.prefs.value.metroAccents[0])
        tap("metro-subdivision-3")
        assertEquals(4, graph.prefs.prefs.value.metroSubdivision)
        tap("metro-bars-on-raise"); tap("metro-bars-on-raise"); tap("metro-bars-off-raise")
        assertEquals(3, graph.prefs.prefs.value.metroBarsOn)
        assertEquals(1, graph.prefs.prefs.value.metroBarsOff)
        tap("metro-click-2"); tap("metro-volume-lower")
        assertEquals(ClickSound.BEEP, graph.prefs.prefs.value.clickSound)
        assertEquals(0.7f, graph.prefs.prefs.value.clickGain, 0.0001f)
        val update = link.sent.filterIsInstance<OutputCommand.MetroUpdate>().last().setup
        assertEquals(graph.prefs.prefs.value.metroSetup(), update.copy(generation = 0))
        tap("metro-metre-5")
        tap("metre-preset-5")
        assertEquals(listOf(Accent.ACCENT, Accent.NORMAL, Accent.NORMAL, Accent.ACCENT, Accent.NORMAL, Accent.ACCENT, Accent.NORMAL), graph.prefs.prefs.value.metroAccents)
        tap("metro-metre-5")
        tap("metre-beats-raise"); tap("metre-unit-3"); tap("metre-use")
        assertEquals(8, graph.prefs.prefs.value.metroBeatsPerBar)
        assertEquals(16, graph.prefs.prefs.value.metroBeatUnit)
        tap("metro-toggle")
        assertFalse(graph.output.state.value.metroWanted)
        assertTrue(link.registered.isEmpty())
    }

    @Test fun `POL-31 native beats move the row through silent bars without recomposing the frame`() {
        launch()
        tap("metro-toggle")
        compose.onNodeWithTag("metro-beats-0").performScrollTo()
        val generation = link.sent.filterIsInstance<OutputCommand.MetroStart>().last().setup.generation
        compose.waitForIdle()
        val before = FrameCompositions.count
        repeat(12) { index ->
            compose.runOnIdle { link.events.tryEmit(OutputEvent.Beat(index = index.toLong(), frame = index * 24_000L, nanos = 0L, beatInBar = index % 4, bar = (index / 4).toLong(), silentBar = index in 4..7, generation = generation, audible = index !in 4..7)) }
            compose.waitForIdle()
            assertEquals(index.toLong(), graph.output.beat.value?.index)
            assertEquals(index in 4..7, graph.output.beat.value?.silentBar)
            compose.onNodeWithTag("metro-beats-0").assertIsSelected().assertContentDescriptionEquals("Beat 1, Accent")
            assertFalse(compose.onNodeWithTag("metro-beats-0").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.LiveRegion))
        }
        assertEquals(before, FrameCompositions.count)
        compose.onNodeWithTag("metro-beats-3").assertContentDescriptionEquals("Beat 4, Normal")
    }

    @Test fun `A11Y-02 every metronome control and custom metre stay reachable at double text`() {
        launch(2f)
        val tags = listOf("metro-bpm", "metro-tap", "metro-toggle", "metro-volume-lower", "metro-volume-raise", "metro-bars-on-raise", "metro-bars-off-raise") +
            (0..5).map { "metro-metre-$it" } + (0..3).map { "metro-beats-$it" } + (0..3).map { "metro-subdivision-$it" } + (0..2).map { "metro-click-$it" }
        for (tag in tags) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            val target = 48 * node.layoutInfo.density.density
            assertTrue("$tag touch target", node.boundsInRoot.width >= target && node.boundsInRoot.height >= target)
        }
        tap("metro-metre-5")
        tap("metre-preset-6")
        assertEquals(9, graph.prefs.prefs.value.metroBeatsPerBar)
        tap("metro-metre-5")
        tap("metre-unit-0"); tap("metre-use")
        assertEquals(2, graph.prefs.prefs.value.metroBeatUnit)
    }
}
