// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.content.Intent
import kotlinx.coroutines.runBlocking
import ticktock.data.history.Scope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import com.github.takahirom.roborazzi.captureRoboImage
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
import ticktock.app.audio.LiveCapture
import ticktock.core.detect.Attack
import ticktock.core.detect.Decision
import ticktock.core.detect.HopClass
import ticktock.core.detect.Reading
import ticktock.core.detect.Release
import ticktock.core.detect.ReleaseCause
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
        skipFirstRun()
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

    private fun needleState(): CharSequence? {
        var state: CharSequence? = null
        scenario!!.onActivity { activity ->
            val view = requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("needle-accessibility"))
            state = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(view.createAccessibilityNodeInfo()).stateDescription
        }
        return state
    }

    private fun statusWords(): List<String> = (0..3).mapNotNull { text("statusbar-$it") }

    private fun awaitStatus(vararg words: String) {
        try {
            compose.waitUntil(TIMEOUT) { statusWords() == words.toList() }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("status ${statusWords()}, wanted ${words.toList()}; readout ${text("readout-letter")}${text("readout-octave")} ${text("readout-cents")}", timeout)
        }
    }

    @Test
    fun `TLK-01 and TLK-02 settled announcements are opt in and never follow cents at hop rate`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        val graph = TickTockApp.graph(ApplicationProvider.getApplicationContext())
        play(input, 440.0, 18)
        compose.waitUntil(TIMEOUT) { text("readout-cents") == "+0" }
        assertEquals(null, text("settled-announcement"))
        assertEquals("B 5, 0 cents", needleState())
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { graph.prefs.update { it.copy(announceSettledNotes = true) } }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(900)
        assertEquals(null, text("settled-announcement"))
        compose.mainClock.advanceTimeBy(150)
        awaitFrames { text("settled-announcement") != null }
        val announcement = text("settled-announcement")
        assertEquals("B 5, 0 cents", announcement)
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite,
            compose.onNodeWithTag("settled-announcement").fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        play(input, Pitch.hzFromMidi(69.07, 440.0), 18)
        awaitFrames { text("readout-cents") != "+0" }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(announcement, text("settled-announcement"))
        play(input, Pitch.hzFromMidi(70, 440.0), 18)
        awaitFrames { text("settled-announcement")?.startsWith("C 6,") == true }
        compose.runOnIdle { graph.prefs.update { it.copy(earsFirst = true) } }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(null, text("settled-announcement"))
    }

    @Test
    fun `TLK-01 needle semantics stay stable while its visual cents change`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        play(input, 440.0, 18)
        compose.waitUntil(TIMEOUT) { text("readout-cents") == "+0" }
        val before = compose.onNodeWithTag("cents-scale", true).fetchSemanticsNode().config
        val description = before.getOrNull(SemanticsProperties.ContentDescription)
        val state = before.getOrNull(SemanticsProperties.StateDescription)
        play(input, Pitch.hzFromMidi(69.07, 440.0), 18)
        compose.waitUntil(TIMEOUT) { text("readout-cents") != "+0" }
        val after = compose.onNodeWithTag("cents-scale", true).fetchSemanticsNode().config
        assertEquals(description, after.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(state, after.getOrNull(SemanticsProperties.StateDescription))
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
    fun `CAP-01 paused capture keeps listening and live plots while history waits until On`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        val graph = TickTockApp.graph(ApplicationProvider.getApplicationContext())
        play(input, 440.0)
        compose.waitUntil(TIMEOUT) { text("readout-cents") == "+0" }
        val saved = storedReadings()
        assertTrue(saved > 0)
        compose.onNodeWithTag("capture-1").performScrollTo().performClick()
        awaitStatus("Listening", "Capture paused")
        compose.onNode(hasTestTag("running") and hasAnyAncestor(hasTestTag("tabs-0")), useUnmergedTree = true).assertExists()
        val oldTrace = graph.tune.trace.value
        val oldHop = graph.tune.level.value.hop
        play(input, 444.0)
        compose.waitUntil(TIMEOUT) { text("readout-cents") == "+16" }
        assertEquals("B 5, 16 cents sharp", needleState())
        assertTrue(graph.tune.trace.value !== oldTrace)
        assertTrue(graph.tune.trace.value.cents.last() > 0f)
        assertTrue(graph.tune.level.value.hop > oldHop)
        compose.onNode(hasAnyAncestor(hasTestTag("level")) and hasText("−23 dB"), useUnmergedTree = true).assertExists()
        assertEquals(saved, storedReadings())
        compose.onNodeWithTag("capture-0").performScrollTo().performClick()
        awaitStatus("Listening")
        play(input, 444.0)
        assertTrue(storedReadings() > saved)
        scenario?.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.waitUntil(TIMEOUT) { !input.isOpen }
        assertTrue(input.stopCalls >= 1)
    }

    private fun storedReadings(): Long = runBlocking {
        val history = TickTockApp.graph(ApplicationProvider.getApplicationContext()).history
        history.flushAndWait()
        history.summaries(Scope.CurrentSession).sumOf { it.n }
    }

    @Test
    fun `EAR-01 ears first hides the needle and preserves every held history reading`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        val graph = TickTockApp.graph(ApplicationProvider.getApplicationContext())
        val sine = Sine(Pitch.hzFromMidi(69 + 0.03, 440.0), 0.1, rate)
        fun hops(count: Int) {
            repeat(count) {
                val previous = graph.tune.trace.value
                input.push(sine.next(Hop.frames(rate)))
                compose.waitUntil(TIMEOUT) { graph.tune.trace.value !== previous }
            }
        }
        hops(12)
        val warm = storedReadings()
        hops(10)
        val shown = storedReadings()
        assertEquals(10L, shown - warm)
        compose.onNodeWithTag("needle-1").performScrollTo().performClick()
        hops(10)
        assertEquals(10L, storedReadings() - shown)
        assertEquals("sounding", text("readout-sounding"))
        assertEquals("— Hz", text("readout-hz"))
        compose.onNodeWithTag("readout-cents", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("—", needleState())
        compose.onNodeWithTag("cents-scale").performScrollTo().captureRoboImage("src/test/screenshots/tune-ears-first-hidden-needle.png")
        compose.onNodeWithTag("last-note-trace").assertExists()
        assertTrue(graph.tune.level.value.db > ticktock.app.audio.Level.LEVEL_FLOOR_DB)
    }

    @Test
    fun `TRC-03 the live trace exposes one stable accessibility label`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        fun assertLabel() {
            val trace = compose.onNodeWithTag("trace").fetchSemanticsNode()
            assertEquals(listOf("Pitch trace, last five seconds"), trace.config[SemanticsProperties.ContentDescription])
            assertTrue(trace.children.isEmpty())
            assertEquals(null, trace.config.getOrNull(SemanticsProperties.Text))
            assertEquals(null, trace.config.getOrNull(SemanticsProperties.LiveRegion))
        }
        assertLabel()
        play(input, 440.0)
        compose.waitUntil(TIMEOUT) { text("readout-letter") != null }
        assertLabel()
    }

    @Test
    fun `ears first hides the cents, Hz and needle while a note sounds, shows its mean for two seconds after it ends, then hides`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        compose.onNodeWithTag("needle-1").performScrollTo().performClick()
        compose.onNodeWithTag("last-note-trace").assertExists()

        // EAR-01: the note name, the sounding mark and the live square show; cents and Hz do not.
        val sine = Sine(Pitch.hzFromMidi(69 + 0.03, 440.0), 0.1, rate)
        repeat(30) { input.push(sine.next(Hop.frames(rate))); Thread.sleep(5) }
        compose.waitUntil(TIMEOUT) { text("readout-sounding") != null }
        assertEquals("B", text("readout-letter"))
        assertEquals("— Hz", text("readout-hz"))
        assertEquals(0, compose.onAllNodes(hasTestTag("readout-cents"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("—", text("last-note"))
        compose.onNodeWithTag("readout").performScrollTo().captureRoboImage("src/test/screenshots/tune-ears-first-hidden.png")

        // EAR-02: the release reveals the held mean, with the countdown under it.
        compose.mainClock.autoAdvance = false
        repeat(30) { input.push(FloatArray(Hop.frames(rate))); Thread.sleep(5) }
        awaitFrames { text("readout-cents") == "+3" }
        assertEquals("440.8 Hz", text("readout-hz"))
        assertEquals("B", text("readout-letter"))
        compose.onNodeWithTag("reveal-countdown", useUnmergedTree = true).assertExists()
        assertTrue(text("last-note").orEmpty(), text("last-note").orEmpty().endsWith(" s"))

        compose.onNodeWithTag("readout").captureRoboImage("src/test/screenshots/tune-ears-first-reveal.png")

        // Two seconds on, the reading hides again and the last note keeps its name and mean.
        compose.mainClock.advanceTimeBy(1_900)
        compose.onNodeWithTag("reveal-countdown", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(200)
        awaitFrames { compose.onAllNodes(hasTestTag("reveal-countdown"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertEquals("—", text("readout-cents"))
        assertTrue(text("last-note").orEmpty(), text("last-note").orEmpty().startsWith("B5 +3 · "))
    }

    @Test
    fun `EAR-02 enabling ears first never replays a note ended in Show`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        decision(0, sounding = true)
        compose.waitUntil(TIMEOUT) { text("readout-cents") == "+3" }
        decision(10, sounding = false)
        compose.waitUntil(TIMEOUT) { text("readout-cents") == "—" }
        compose.onNodeWithTag("needle-1").performScrollTo()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("needle-1").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("last-note-trace").assertExists()
        compose.onNodeWithTag("reveal-countdown", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("—", text("readout-cents"))
    }

    @Test
    fun `EAR-02 a new note without readings cancels the previous reveal permanently`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        compose.onNodeWithTag("needle-1").performScrollTo().performClick()
        decision(0, sounding = true)
        compose.waitUntil(TIMEOUT) { text("readout-sounding") != null }
        compose.mainClock.autoAdvance = false
        decision(10, sounding = false)
        awaitFrames { text("readout-cents") == "+3" }
        decision(11, sounding = true, withReading = false)
        awaitFrames { text("readout-sounding") != null }
        decision(12, sounding = false)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("reveal-countdown", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("—", text("readout-cents"))
    }

    @Test
    fun `EAR-02 a short note reveals a ghost attack when no settled reading exists`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        compose.onNodeWithTag("needle-1").performScrollTo().performClick()
        decision(0, sounding = true)
        compose.waitUntil(TIMEOUT) { text("readout-sounding") != null }
        compose.mainClock.autoAdvance = false
        decision(1, sounding = false)
        awaitFrames { text("readout-cents") == "+3" }
        assertTrue(text("last-note").orEmpty(), text("last-note").orEmpty().startsWith("attack — · "))
    }

    @Test
    fun `Names is hidden for C instruments and keeps the saved choice for transposing instruments`() {
        launch(null)
        val graph = TickTockApp.graph(ApplicationProvider.getApplicationContext())
        compose.runOnIdle {
            graph.prefs.update { it.copy(instrumentKey = ticktock.core.catalogue.Catalogue.get("flute_c").key,
                displayMode = ticktock.core.config.DisplayMode.CONCERT) }
        }
        compose.onNodeWithTag("names").assertDoesNotExist()
        assertEquals(ticktock.core.config.DisplayMode.CONCERT, graph.prefs.prefs.value.displayMode)
        compose.runOnIdle { graph.prefs.update { it.copy(instrumentKey = ticktock.core.catalogue.Catalogue.DEFAULT_KEY) } }
        compose.onNodeWithTag("names-1").assertIsSelected()
    }

    /** Script release edges so clock tests cover notes with and without accepted readings. */
    private fun decision(hop: Long, sounding: Boolean, withReading: Boolean = true) {
        val session = TickTockApp.graph(ApplicationProvider.getApplicationContext()).tune
        val field = session.javaClass.getDeclaredField("capture").apply { isAccessible = true }
        val capture = field.get(session) as LiveCapture
        val decision = Decision(
            hop, if (sounding) HopClass.VOTE else HopClass.REJECT,
            level = if (sounding) 0.1 else 0.0, incumbent = if (sounding) 69 else null,
            attack = if (sounding) Attack(hop, 69, 3.0, 0) else null,
            display = if (sounding && withReading) Reading(hop, 69, 440.8, 3.0, 440.0, 0) else null,
            release = if (sounding) null else Release(hop, 69, 0, ReleaseCause.SILENCE),
        )
        val method = LiveCapture::class.java.getDeclaredMethod("onDecisions", List::class.java).apply { isAccessible = true }
        method.invoke(capture, listOf(decision))
    }

    /** Steps the paused clock a frame at a time until [condition] holds, letting the analysis thread's flows land between frames. */
    private fun awaitFrames(condition: () -> Boolean) {
        val until = System.nanoTime() + TIMEOUT * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("readout ${text("readout-letter")} ${text("readout-cents")} ${text("readout-hz")}; last note ${text("last-note")}")
            Thread.sleep(10)
            compose.mainClock.advanceTimeByFrame()
        }
    }

    @Test
    fun `choosing alto sax in Settings makes a concert A4 read written G flat 5 in a wind player's flats, and Sharps spells it F sharp`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        compose.waitUntil(TIMEOUT) { input.isOpen }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("setting-instrument").performScrollTo().performClick()
        compose.onNodeWithTag("titlebar-title", useUnmergedTree = true).assertTextEquals("Instrument")
        compose.onNodeWithTag("instrument-eb_alto").performScrollTo().performClick()
        compose.onNodeWithTag("setting-instrument-value", useUnmergedTree = true).assertTextEquals("Alto saxophone in Eb")
        compose.onNodeWithTag("back").performClick()

        play(input, Pitch.hzFromMidi(69, 440.0))
        compose.waitUntil(TIMEOUT) { text("readout-letter") == "G♭" }
        assertEquals("5", text("readout-octave"))

        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("spelling-1").performScrollTo().performClick()
        compose.onNodeWithTag("back").performClick()
        play(input, Pitch.hzFromMidi(69, 440.0), hops = 4)
        compose.waitUntil(TIMEOUT) { text("readout-letter") == "F♯" }
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
