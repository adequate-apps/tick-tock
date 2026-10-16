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
import ticktock.app.screens.ScreenRenderProbe
import ticktock.core.contracts.OutputTool

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class ScreenRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun close() {
        ScreenRenderProbe.beforeRender = null
        scenario?.close()
    }

    @Test fun `ERR-01 a real screen composition failure leaves navigation and sounding tools intact`() {
        val context = RuntimeEnvironment.getApplication()
        val graph = TickTockApp.graph(context)
        val link = TestOutputConnection()
        graph.tune.micAllowed = { false }
        graph.output.makeConnection = { link }
        graph.prefs.update { it.copy(firstRunComplete = true, metroBpm = 137, droneMidi = 57) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.runOnIdle { graph.output.startDrone(); graph.output.startMetro() }
        val commandsBefore = link.sent.toList()
        var throws = 0
        ScreenRenderProbe.beforeRender = { screen ->
            if (screen == "metronome") {
                throws++
                throw IllegalStateException("Planted screen render failure")
            }
        }

        compose.onNodeWithTag("tabs-2").performClick()
        compose.onNodeWithTag("screen-failure-metronome").assertIsDisplayed()
        compose.onNodeWithTag("screen-share-log").assertTextEquals("Share log").assertHasClickAction()
        assertEquals("the failed composition is not retried automatically", 1, throws)
        assertSame(graph, TickTockApp.graph(context))
        assertEquals(setOf(OutputTool.DRONE, OutputTool.METRONOME), link.registered)
        assertTrue(graph.output.state.value.droneSounding)
        assertTrue(graph.output.state.value.metroSounding)
        assertEquals(commandsBefore, link.sent)

        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("drone-toggle").assertIsDisplayed().assertTextEquals("Stop")
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-ears-first-0").performScrollTo().performClick()
        assertTrue(graph.prefs.prefs.value.earsFirst)
        assertEquals(137, graph.prefs.prefs.value.metroBpm)
        assertEquals(57, graph.prefs.prefs.value.droneMidi)
        assertEquals(commandsBefore, link.sent)
    }
}
