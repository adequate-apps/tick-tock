// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.app.debug.InputSeam
import ticktock.core.contracts.InputFailure
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.core.music.Pitch

/**
 * The debug readout on the harness input: audio pushed into the fake
 * microphone comes out as a note name, whole cents, Hz, a level and the
 * input in use, and a plain launch shows no readout at all.
 */
@RunWith(AndroidJUnit4::class)
class DebugReadoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val rate = 48_000

    @After
    fun close() {
        scenario?.close()
        InputSeam.override = null
    }

    @Test
    fun `a plain launch shows the Tune pane without the readout`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("tune-empty", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("debug-readout-titlebar", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a held A4 shows its name, cents, Hz, level and the input, and the pane says it is listening`() {
        val input = FakeAudioInput(sampleRate = rate, deviceName = "Fake microphone")
        InputSeam.override = input
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        compose.onNodeWithTag("debug-readout-titlebar-state", useUnmergedTree = true).assertTextEquals("Listening")
        compose.onNodeWithTag("debug-note-value", useUnmergedTree = true).assertTextEquals("–")

        val sine = Sine(Pitch.hzFromMidi(69, 440.0), 0.1, rate)
        repeat(8) { input.push(sine.next(Hop.frames(rate))) }

        compose.waitUntil(TIMEOUT) { nodes("debug-note-value", "A4") == 1 }
        compose.onNodeWithTag("debug-cents-value", useUnmergedTree = true).assertTextEquals("+0 c")
        compose.onNodeWithTag("debug-hz-value", useUnmergedTree = true).assertTextEquals("440.0 Hz")
        // A sine at amplitude 0.1 has an RMS of 0.0707, which is -23 dBFS.
        compose.onNodeWithTag("debug-level-value", useUnmergedTree = true).assertTextEquals("-23 dBFS")
        compose.onNodeWithTag("debug-source-value", useUnmergedTree = true).assertTextEquals("FAKE at 48000 Hz, Fake microphone")
        compose.waitUntil(TIMEOUT) { nodes("debug-counters-value", "8 hops, 0 dropped, 0 errors", substring = true) == 1 }

        scenario?.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.waitUntil(TIMEOUT) { !input.isOpen }
        assertEquals(0, input.openCount)
    }

    @Test
    fun `a microphone that will not open is a sentence naming the device`() {
        val input = FakeAudioInput(sampleRate = rate, deviceName = "Built-in microphone")
        input.failNextStart = InputFailure.Busy("Built-in microphone")
        InputSeam.override = input
        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(TIMEOUT) { nodes("debug-readout-titlebar-state", "Stopped: Built-in microphone is in use by another app") == 1 }
        compose.onNodeWithTag("debug-source-value", useUnmergedTree = true).assertTextEquals("No input open")
        assertTrue(input.startCalls >= 1)
    }

    private fun nodes(tag: String, text: String, substring: Boolean = false): Int =
        compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().size

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
