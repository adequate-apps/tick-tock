// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.app.debug.InputSeam
import ticktock.app.debug.OutputSeam
import ticktock.app.output.TestOutputConnection
import ticktock.core.contracts.*

@RunWith(AndroidJUnit4::class)
class DebugOutputTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val link = TestOutputConnection()
    private lateinit var graph: AppGraph
    @After fun close() { scenario?.close() }

    private fun launch(drone: Int? = null, metro: Int? = null, a4: Float? = null) {
        skipFirstRun()
        graph = TickTockApp.graph(ApplicationProvider.getApplicationContext())
        graph.output.makeConnection = { link }
        graph.tune.micAllowed = { false }
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        drone?.let { intent.putExtra(OutputSeam.EXTRA_DRONE_MIDI, it) }
        metro?.let { intent.putExtra(OutputSeam.EXTRA_METRO_BPM, it) }
        a4?.let { intent.putExtra(InputSeam.EXTRA_A4_HZ, it) }
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    @Test fun `plain launch leaves the shared engine unopened`() {
        launch()
        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("drone-readout", true).assertExists()
        assertTrue(link.sent.isEmpty())
    }

    @Test fun `OUT-01 debug extras start both tools through the shared owner and background stops them`() {
        launch(69, 120)
        assertEquals(setOf(OutputTool.DRONE, OutputTool.METRONOME), link.registered)
        assertTrue(link.subscribedAtFirstRegister)
        compose.onNodeWithTag("tabs-2").performClick()
        compose.onNodeWithTag("open-settings").performClick()
        assertTrue(graph.output.state.value.droneSounding && graph.output.state.value.metroSounding)
        scenario!!.moveToState(Lifecycle.State.CREATED)
        assertTrue(link.registered.isEmpty())
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        assertFalse(graph.output.state.value.toolRunning)
    }

    @Test fun `TON-02 launch A4 is applied before VoiceOn and is shown by the real pane`() {
        launch(69, a4 = 442f)
        assertEquals(OutputCommand.SetA4(442.0), link.sent.first())
        assertEquals(69, link.sent.filterIsInstance<OutputCommand.VoiceOn>().single().midi)
        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("drone-hz", true).assertTextEquals("442.0 Hz")
    }

    @Test fun `a metronome extra registers only the metronome`() {
        launch(metro = 96)
        assertEquals(setOf(OutputTool.METRONOME), link.registered)
        assertEquals(96, link.sent.filterIsInstance<OutputCommand.MetroStart>().single().setup.bpm)
        assertTrue(link.sent.none { it is OutputCommand.VoiceOn })
    }

    @Test fun `OUT-17 refusal names the device and rates on the real pane`() {
        launch(57)
        compose.runOnIdle { link.info.value = null; link.events.tryEmit(OutputEvent.Failed("USB DAC", listOf(48_000, 44_100))) }
        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("output-notice-text", true).performScrollTo().assertTextEquals("USB DAC refused 48000, 44100 Hz")
    }

    @Test fun `consumed launch extras cannot restart a stopped tool during recreation`() {
        launch(69, 120)
        compose.runOnIdle { graph.output.stopDrone(); graph.output.stopMetro() }
        val starts = link.sent.count { it is OutputCommand.VoiceOn || it is OutputCommand.MetroStart }
        scenario!!.recreate()
        compose.waitForIdle()
        assertEquals(starts, link.sent.count { it is OutputCommand.VoiceOn || it is OutputCommand.MetroStart })
        assertFalse(graph.output.state.value.toolRunning)
    }
}
