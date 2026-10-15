// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

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
import ticktock.core.catalogue.Catalogue
import ticktock.core.config.DisplayMode
import ticktock.core.config.Spelling
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.Voice

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class DroneScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private val link = TestOutputConnection()

    private fun launch(scale: Float = 1f, instrument: String = "eb_alto") {
        RuntimeEnvironment.setFontScale(scale)
        graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.tune.micAllowed = { false }
        graph.output.makeConnection = { link }
        graph.prefs.update { it.copy(firstRunComplete = true, instrumentKey = instrument, droneMidi = 69,
            a4Hz = 442.0, spelling = Spelling.SHARPS) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("tabs-1").performClick()
    }

    @After
    fun close() { scenario?.close(); RuntimeEnvironment.setFontScale(1f) }

    private fun tap(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag != "drone-toggle" || !graph.output.state.value.droneWanted) node.performScrollTo()
        node.performClick()
    }
    private fun latestVoice(): OutputCommand.VoiceOn = link.sent.filterIsInstance<OutputCommand.VoiceOn>().last()

    @Test
    fun `OUT-02 parked and muted drone actions describe the actual state and cancel resume`() {
        launch()
        tap("drone-toggle")
        compose.runOnIdle { link.park(ticktock.core.contracts.ParkReason.HEADPHONES_DISCONNECTED) }
        compose.onNodeWithTag("drone-toggle").assertTextEquals("Cancel resume")
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Paused"))
        tap("drone-toggle")
        compose.runOnIdle { link.replug() }
        assertFalse(graph.output.state.value.droneWanted)
        tap("drone-toggle")
        compose.runOnIdle { link.controlDiagnostics.value = link.controlDiagnostics.value.copy(
            muteSources = setOf(ticktock.engine.output.MuteSource.CALL_RINGING)) }
        compose.onNodeWithTag("drone-toggle").assertTextEquals("Cancel resume")
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Muted"))
        tap("drone-toggle")
        assertFalse(graph.output.state.value.droneWanted)
    }

    @Test
    fun `CHK-01 spoken pads include naming context secondary pitch and tuning marks`() {
        launch()
        compose.onNodeWithTag("drone-ensemble-0").assertContentDescriptionEquals("Concert B♭, written G5")
        compose.onNodeWithTag("drone-ensemble-1").assertContentDescriptionEquals("Concert A, written F♯5")
        compose.onNodeWithTag("drone-pads-6").assertContentDescriptionEquals("Written F♯5, concert A4, ensemble, check")
        compose.onNodeWithTag("drone-pads-0").assertContentDescriptionEquals("Written C5, concert D♯4")
        tap("drone-names-1")
        compose.onNodeWithTag("drone-pads-9").assertContentDescriptionEquals("Concert A4, ensemble")
        compose.onNodeWithTag("drone-ensemble-1").assertContentDescriptionEquals("Concert A")
    }

    @Test
    fun `a sustained drone has an immediate visible stop even while scrolling at double text`() {
        launch(2f)
        tap("drone-ensemble-0")
        compose.onAllNodesWithText("Stop").assertCountEquals(1)
        compose.onNodeWithTag("drone-toggle").assertIsDisplayed().assertTextEquals("Stop").performClick()
        assertFalse(graph.output.state.value.droneWanted)
        compose.onNodeWithTag("drone-toggle").assertTextEquals("Start")
    }

    @Test
    fun `TON-01 and TON-04 every drone control reaches the shared owner and navigation keeps it running`() {
        launch()
        assertTrue(link.sent.isEmpty())
        compose.onNodeWithTag("drone-note", useUnmergedTree = true).assertTextEquals("F♯")
        compose.onNodeWithTag("drone-other", useUnmergedTree = true).assertTextEquals("concert A4")
        compose.onNodeWithTag("drone-hz", useUnmergedTree = true).assertTextEquals("442.0 Hz")
        compose.onNodeWithTag("drone-ensemble-0").performScrollTo().assertIsDisplayed()
        val ensembleA = compose.onNodeWithTag("drone-ensemble-0").fetchSemanticsNode().boundsInRoot
        val ensembleB = compose.onNodeWithTag("drone-ensemble-1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(ensembleA.top, ensembleB.top, 0f)
        tap("drone-pads-0")
        assertEquals(63, latestVoice().midi)
        assertEquals(OutputCommand.SetA4(442.0), link.sent.first())
        assertEquals(setOf(OutputTool.DRONE), link.registered)
        tap("drone-voice-0")
        assertEquals(Voice.SINE, latestVoice().voice)
        val strikes = link.sent.count { it is OutputCommand.VoiceOn }
        tap("drone-volume-raise")
        assertEquals(strikes, link.sent.count { it is OutputCommand.VoiceOn })
        assertEquals(0.6f, graph.prefs.prefs.value.droneGain, 0.0001f)
        assertTrue(link.sent.last() is OutputCommand.VoiceGain)
        tap("drone-ensemble-0")
        assertEquals(70, latestVoice().midi)
        tap("drone-ensemble-1")
        assertEquals(69, latestVoice().midi)
        tap("drone-octave-raise")
        assertEquals(81, latestVoice().midi)
        tap("drone-octave-lower")
        assertEquals(69, latestVoice().midi)
        tap("drone-next")
        assertEquals(64, latestVoice().midi)
        tap("drone-next")
        assertEquals(65, latestVoice().midi)
        tap("drone-next")
        assertEquals(69, latestVoice().midi)
        compose.onNodeWithTag("tabs-0").performClick()
        assertTrue(graph.output.state.value.droneSounding)
        compose.onNodeWithTag("open-settings").performClick()
        assertTrue(graph.output.state.value.droneSounding)
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("tabs-1").performClick()
        tap("drone-toggle")
        assertFalse(graph.output.state.value.droneWanted)
        assertTrue(link.registered.isEmpty())
        assertEquals(69, graph.prefs.prefs.value.droneMidi)
        tap("drone-toggle")
        assertTrue(graph.output.state.value.droneSounding)
        scenario!!.recreate()
        compose.waitForIdle()
        assertTrue(graph.output.state.value.droneWanted)
        assertEquals(69, graph.prefs.prefs.value.droneMidi)
        assertEquals(Voice.SINE, graph.prefs.prefs.value.droneVoice)
    }

    @Test
    fun `CHK-01 written check marks disappear in Concert while ensemble marks and shared preference remain`() {
        launch()
        compose.onAllNodesWithText("check", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithText("ensemble", useUnmergedTree = true).assertCountEquals(2)
        tap("drone-names-1")
        assertEquals(DisplayMode.CONCERT, graph.prefs.prefs.value.displayMode)
        compose.onAllNodesWithText("check", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("ensemble", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithTag("drone-note", useUnmergedTree = true).assertTextEquals("A")
        compose.runOnIdle { graph.prefs.update { it.copy(instrumentKey = "flute_c") } }
        compose.onNodeWithTag("drone-names").assertDoesNotExist()
        assertEquals(DisplayMode.CONCERT, graph.prefs.prefs.value.displayMode)
    }

    @Test
    fun `A11Y-02 long instruments and every drone control remain reachable at double text`() {
        val instrument = Catalogue.all.single { it.displayName == "Contrabass clarinet in Bb" }.key
        launch(2f, instrument)
        compose.onNodeWithTag("drone-instrument", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            .assertTextEquals("Contrabass clarinet in Bb")
        val controls = listOf("drone-ensemble-0", "drone-ensemble-1") +
            (0 until 12).map { "drone-pads-$it" } + listOf("drone-octave-lower", "drone-octave-raise",
                "drone-volume-lower", "drone-volume-raise", "drone-voice-0", "drone-voice-1", "drone-voice-2", "drone-next", "drone-toggle")
        for (tag in controls) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            val target = 48 * node.layoutInfo.density.density
            assertTrue("$tag touch target", node.boundsInRoot.width >= target && node.boundsInRoot.height >= target)
        }
    }
}
