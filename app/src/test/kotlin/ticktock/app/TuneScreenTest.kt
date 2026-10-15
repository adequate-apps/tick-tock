// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import ticktock.app.debug.InputSeam
import ticktock.core.contracts.InputFailure
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.core.music.Pitch

/**
 * The Tune pane end to end on the harness input: audio pushed into a fake
 * microphone runs through the real analysis and comes out as the written
 * note, its cents and band, the concert name, the status words and the
 * running square; the gates show their key and no prose.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TuneScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val rate = 48_000

    @After
    fun close() {
        scenario?.close()
        InputSeam.override = null
    }

    private fun launch(input: FakeAudioInput?, a4: Float? = null) {
        InputSeam.override = input
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        a4?.let { intent.putExtra(InputSeam.EXTRA_A4_HZ, it) }
        scenario = ActivityScenario.launch(intent)
    }

    private fun play(input: FakeAudioInput, hz: Double, hops: Int = 10) {
        val sine = Sine(hz, 0.1, rate)
        repeat(hops) {
            input.push(sine.next(Hop.frames(rate)))
            Thread.sleep(5)
        }
    }

    private fun text(tag: String): String? =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    private fun statusWords(): List<String> = (0..3).mapNotNull { text("statusbar-$it") }

    private fun awaitStatus(vararg words: String) {
        try {
            compose.waitUntil(TIMEOUT) { statusWords() == words.toList() }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("status ${statusWords()}, wanted ${words.toList()}; readout ${text("readout-letter")}${text("readout-octave")} ${text("readout-cents")}", timeout)
        }
    }

    @Test
    fun `a plain launch with no microphone permission is the ghost readout with one key and Mic off`() {
        launch(null)
        compose.onNodeWithTag("gate-mic_not_asked").assertIsDisplayed()
        compose.onNodeWithTag("allow-mic").assertIsDisplayed()
        assertEquals("—", text("readout-note"))
        assertEquals("— Hz", text("readout-hz"))
        assertEquals(listOf("Mic off"), statusWords())
    }

    @Test
    fun `a played A4 on the default tenor sax reads written B5, in tune, concert A4, and the pane is listening`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        awaitStatus("Listening")

        play(input, Pitch.hzFromMidi(69, 440.0))
        compose.waitUntil(TIMEOUT) { text("readout-letter") != null }
        assertEquals("B", text("readout-letter"))
        assertEquals("5", text("readout-octave"))
        assertEquals("+0", text("readout-cents"))
        assertEquals("in tune", text("readout-band"))
        assertEquals("440.0 Hz", text("readout-hz"))
        assertEquals("concert A4", text("readout-other"))
        compose.onNode(hasTestTag("running") and hasAnyAncestor(hasTestTag("tabs-0")), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `Concert names the sounding note and the stepper and status follow A4`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input, a4 = 442f)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        compose.onNodeWithTag("names-1").performScrollTo().performClick()
        compose.onNodeWithTag("a4-value", useUnmergedTree = true).assertTextEquals("442 Hz")

        play(input, 442.0)
        compose.waitUntil(TIMEOUT) { text("readout-letter") == "A" }
        assertEquals("4", text("readout-octave"))
        assertEquals("+0", text("readout-cents"))
        assertEquals("written B5", text("readout-other"))
        awaitStatus("Listening", "A4 442")

        compose.onNodeWithTag("a4-lower").performScrollTo().performClick()
        awaitStatus("Listening", "A4 441")
    }

    @Test
    fun `a sharp note shows the sharp cue, the close band and its cents`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        compose.onNodeWithTag("names-1").performScrollTo().performClick()
        play(input, Pitch.hzFromMidi(69 + 0.09, 440.0))
        compose.waitUntil(TIMEOUT) { text("readout-cents") == "+9" }
        assertEquals("close", text("readout-band"))
    }

    @Test
    fun `a microphone held by another app is Mic busy with Try again, which reopens it`() {
        val input = FakeAudioInput(sampleRate = rate)
        input.failNextStart = InputFailure.Busy("Built-in microphone")
        launch(input)
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasTestTag("gate-mic_busy")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Held by another app", text("gate-mic_busy-text"))
        assertEquals(listOf("Mic busy"), statusWords())

        compose.onNodeWithTag("try-again").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) { input.isOpen }
        awaitStatus("Listening")
    }

    @Test
    fun `Paused capture keeps the readout and says Paused, and leaving the app closes the microphone`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        compose.onNodeWithTag("capture-1").performScrollTo().performClick()
        awaitStatus("Paused")
        play(input, Pitch.hzFromMidi(69, 440.0))
        compose.waitUntil(TIMEOUT) { text("readout-letter") != null }

        scenario?.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.waitUntil(TIMEOUT) { !input.isOpen }
        assertTrue(input.stopCalls >= 1)
    }

    @Test
    fun `ears first hides the cents until the note has been held two seconds`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        compose.onNodeWithTag("needle-1").performScrollTo().performClick()
        val sine = Sine(Pitch.hzFromMidi(69, 440.0), 0.1, rate)
        repeat(10) { input.push(sine.next(Hop.frames(rate))) }
        compose.waitUntil(TIMEOUT) { text("readout-letter") != null }
        assertEquals("the cents wait for the reveal", 0, compose.onAllNodes(hasTestTag("readout-cents"), useUnmergedTree = true).fetchSemanticsNodes().size)
        // Keep the note sounding past the reveal.
        val until = System.nanoTime() + 2_600_000_000L
        while (System.nanoTime() < until) {
            input.push(sine.next(Hop.frames(rate)))
            Thread.sleep(40)
            compose.mainClock.advanceTimeBy(40)
        }
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasTestTag("readout-cents") and hasText("+0"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `POL-31 five seconds of a held note recompose the frame not once`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        val sine = Sine(Pitch.hzFromMidi(69, 440.0), 0.1, rate)
        repeat(12) { input.push(sine.next(Hop.frames(rate))); Thread.sleep(5) }
        compose.waitUntil(TIMEOUT) { text("readout-letter") != null }
        compose.waitForIdle()
        val before = ticktock.app.screens.FrameCompositions.count
        repeat(Hop.count(5.0)) {
            input.push(sine.next(Hop.frames(rate)))
            Thread.sleep(5)
            if (it % 10 == 0) compose.waitForIdle()
        }
        compose.waitForIdle()
        assertEquals("frame compositions during a held note", 0, ticktock.app.screens.FrameCompositions.count - before)
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
