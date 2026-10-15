// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.app.output.TestOutputConnection
import ticktock.core.config.DisplayMode
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.prefs.Theme

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w600dp-h800dp-xhdpi")
class OutputScreenshotTest(private val scale: Float, private val theme: Theme) {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private val link = TestOutputConnection()
    @After fun close() { scenario?.close(); RuntimeEnvironment.setFontScale(1f) }
    private fun launch() {
        RuntimeEnvironment.setFontScale(scale)
        graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.tune.micAllowed = { false }
        graph.output.makeConnection = { link }
        graph.prefs.update { it.copy(firstRunComplete = true, theme = theme, instrumentKey = "eb_alto", droneMidi = 57,
            metroBpm = 92, metroSubdivision = 2, metroBarsOn = 3, metroBarsOff = 1) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }
    private fun golden(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/output-$name-600x800-fs$scale-${theme.name.lowercase()}.png")

    @Test fun `running drone retains all controls and both naming modes on a wide screen`() {
        launch()
        compose.runOnIdle { graph.output.startDrone() }
        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("drone-readout", true).performScrollTo().assertIsDisplayed()
        golden("drone")
        compose.onNodeWithTag("drone-next").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drone-toggle").assertIsDisplayed()
        golden("drone-controls")
        compose.runOnIdle { graph.prefs.update { it.copy(displayMode = DisplayMode.CONCERT) } }
        compose.onAllNodesWithText("check", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("drone-readout", true).performScrollTo()
        golden("drone-concert")
    }

    @Test fun `metronome shows the native silent bar and keeps its lower controls reachable`() {
        launch()
        compose.runOnIdle { graph.output.startMetro() }
        compose.onNodeWithTag("tabs-2").performClick()
        val generation = link.sent.filterIsInstance<OutputCommand.MetroStart>().last().setup.generation
        compose.runOnIdle { link.events.tryEmit(OutputEvent.Beat(index = 13, beatInBar = 1, frame = 1000, nanos = 0, bar = 3, silentBar = true, generation = generation, audible = false)) }
        compose.waitForIdle()
        assertEquals(true, graph.output.beat.value?.silentBar)
        compose.onNodeWithTag("metro-bpm").performScrollTo().assertIsDisplayed()
        golden("metronome-gap")
        compose.onNodeWithTag("metro-volume-raise").performScrollTo().assertIsDisplayed()
        golden("metronome-controls")
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "fs{0}-{1}")
        fun cases(): List<Array<Any>> = listOf(1f, 1.3f, 1.5f, 2f).map { arrayOf<Any>(it, Theme.GRIDLINE) } +
            Theme.entries.filter { it != Theme.GRIDLINE }.map { arrayOf<Any>(1f, it) }
    }
}
