// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.app.debug.InputSeam
import ticktock.app.debug.OutputSeam
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputInfo
import ticktock.core.contracts.OutputPort
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.Voice
import ticktock.core.contracts.fakes.FakeOutputPort

/**
 * The debug output pane on the fake port: the launch extras start the drone
 * and the metronome through the one door to the engine, every event the
 * port reports becomes a row or the pane's sentence, and leaving the screen
 * stops exactly what was started. A plain launch shows no output pane.
 */
@RunWith(AndroidJUnit4::class)
class DebugOutputTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val port = FakeOutputPort()

    @After
    fun close() {
        scenario?.close()
        OutputSeam.override = null
    }

    private fun launch(droneMidi: Int? = null, metroBpm: Int? = null) {
        OutputSeam.override = port
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        droneMidi?.let { intent.putExtra(OutputSeam.EXTRA_DRONE_MIDI, it) }
        metroBpm?.let { intent.putExtra(OutputSeam.EXTRA_METRO_BPM, it) }
        scenario = ActivityScenario.launch(intent)
        openDrone()
    }

    /** The debug output pane sits on the Drone tab until M5 replaces it with the real pane. */
    private fun openDrone() {
        compose.onNodeWithTag("tabs-1").performClick()
    }

    private fun value(tag: String) = compose.onNodeWithTag("$tag-value", useUnmergedTree = true)

    private fun nodes(tag: String, text: String): Int =
        compose.onAllNodes(hasTestTag(tag) and hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `a plain launch shows no output pane`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        openDrone()
        compose.onNodeWithTag("drone-empty", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("debug-output-titlebar", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(emptyList<OutputCommand>(), port.sent)
    }

    @Test
    fun `OUT-01 the extras register the drone and the metronome, the pane shows the stream and the beats, and leaving stops both`() {
        launch(droneMidi = 69, metroBpm = 120)
        compose.waitUntil(TIMEOUT) { port.sent.size >= 4 }
        assertEquals(
            listOf(
                OutputCommand.Register(OutputTool.DRONE),
                OutputCommand.VoiceOn(OutputSeam.DRONE_SLOT, 69, Voice.SINE, OutputSeam.DRONE_GAIN),
                OutputCommand.Register(OutputTool.METRONOME),
                OutputCommand.MetroStart(OutputSeam.metroSetup(120)),
            ),
            port.sent.toList(),
        )
        compose.waitUntil(TIMEOUT) { nodes("debug-output-titlebar-state", "Open on Fake speaker at 48000 Hz") == 1 }
        value("debug-route").assertTextEquals("Fake speaker, SPEAKER, 240 frames per block, 20 ms")
        compose.waitUntil(TIMEOUT) { nodes("debug-last-event-value", "Opened") == 1 }
        value("debug-drone").assertTextEquals("A4 (MIDI 69), sine, gain 0.5")
        value("debug-metro").assertTextEquals("120 bpm, 4/4, wood")
        value("debug-beats").assertTextEquals("No beats yet")
        value("debug-counters").assertTextEquals("No counters yet")

        port.beat(index = 0, beatInBar = 0, frame = 0, nanos = 1_000)
        port.beat(index = 1, beatInBar = 1, frame = 24_000, nanos = 501_000_000)
        port.emit(OutputEvent.Counters(lateBeatsDropped = 1, underruns = 2))
        compose.waitUntil(TIMEOUT) { nodes("debug-beats-value", "2 beats; last index 1 at beat 1 of the bar, frame 24000") == 1 }
        value("debug-counters").assertTextEquals("1 late beats dropped, 2 underruns")

        port.unplug()
        compose.waitUntil(TIMEOUT) { nodes("debug-output-titlebar-state", "Paused: headphones disconnected") == 1 }

        scenario?.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(TIMEOUT) { port.sent.size >= 8 }
        assertEquals(
            listOf(
                OutputCommand.MetroStop,
                OutputCommand.Unregister(OutputTool.METRONOME),
                OutputCommand.VoiceOff(OutputSeam.DRONE_SLOT),
                OutputCommand.Unregister(OutputTool.DRONE),
            ),
            port.sent.drop(4),
        )
    }

    @Test
    fun `TON-02 the A4 extra retunes the engine before the drone strikes`() {
        OutputSeam.override = port
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(OutputSeam.EXTRA_DRONE_MIDI, 69)
            .putExtra(InputSeam.EXTRA_A4_HZ, 442f)
        scenario = ActivityScenario.launch(intent)
        openDrone()
        compose.waitUntil(TIMEOUT) { port.sent.size >= 3 }
        assertEquals(
            listOf(
                OutputCommand.Register(OutputTool.DRONE),
                OutputCommand.SetA4(442.0),
                OutputCommand.VoiceOn(OutputSeam.DRONE_SLOT, 69, Voice.SINE, OutputSeam.DRONE_GAIN),
            ),
            port.sent.toList(),
        )
        value("debug-drone").assertTextEquals("A4 (MIDI 69) at A4 = 442 Hz, sine, gain 0.5")
    }

    @Test
    fun `a metronome alone registers one tool and the drone row says off`() {
        launch(metroBpm = 96)
        compose.waitUntil(TIMEOUT) { port.sent.size >= 2 }
        assertEquals(
            listOf(OutputCommand.Register(OutputTool.METRONOME), OutputCommand.MetroStart(OutputSeam.metroSetup(96))),
            port.sent.toList(),
        )
        compose.waitUntil(TIMEOUT) { nodes("debug-drone-value", "Off") == 1 }
        value("debug-metro").assertTextEquals("96 bpm, 4/4, wood")
    }

    @Test
    fun `OUT-17 a refused open is the pane's sentence, naming the device and the rates`() {
        launch(droneMidi = 57)
        compose.waitUntil(TIMEOUT) { port.sent.size >= 2 }
        port.fail("USB DAC", listOf(48_000, 44_100))
        compose.waitUntil(TIMEOUT) { nodes("debug-output-titlebar-state", "Output failed: USB DAC refused the rates 48000, 44100") == 1 }
        value("debug-drone").assertTextEquals("A3 (MIDI 57), sine, gain 0.5")
    }

    @Test
    fun `OUT-02 the Opened event is seen on a port that replays nothing, so the pane never shows a live stream as closed`() {
        val strict = NoReplayPort()
        OutputSeam.override = strict
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(OutputSeam.EXTRA_DRONE_MIDI, 69)
        scenario = ActivityScenario.launch(intent)
        openDrone()
        compose.waitUntil(TIMEOUT) { strict.sent.size >= 2 }
        // The port emitted Opened inside Register; a collector that subscribed later would never see it.
        compose.waitUntil(TIMEOUT) { nodes("debug-last-event-value", "Opened") == 1 }
    }

    /**
     * The real port's shape: events are emitted on the engine's thread with
     * no replay, so a collector that arrives late sees nothing. Opened is
     * emitted synchronously inside the first Register and info is published
     * through the event alone.
     */
    private class NoReplayPort : OutputPort {
        val sent = mutableListOf<OutputCommand>()
        private val eventFlow = MutableSharedFlow<OutputEvent>(extraBufferCapacity = 64)
        override val events: Flow<OutputEvent> get() = eventFlow
        override val info = MutableStateFlow<OutputInfo?>(null)

        override fun send(cmd: OutputCommand) {
            sent += cmd
            if (cmd is OutputCommand.Register && sent.count { it is OutputCommand.Register } == 1) {
                eventFlow.tryEmit(OutputEvent.Opened(FakeOutputPort.DEFAULT_INFO))
            }
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
