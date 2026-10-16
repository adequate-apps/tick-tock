// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.app.debug.InputSeam
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.Reading
import ticktock.core.detect.fakes.Sine
import ticktock.core.music.Pitch
import ticktock.core.prefs.Prefs
import ticktock.data.history.Scope

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class DisplayAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private val rate = 48_000

    @After fun close() {
        scenario?.close()
        InputSeam.override = null
    }

    private fun launch(input: FakeAudioInput? = null, a4: Double = 440.0) {
        skipFirstRun(Prefs(firstRunComplete = true, instrumentKey = "flute_c", a4Hz = a4))
        InputSeam.override = input
        scenario = ActivityScenario.launch(Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java))
        graph = TickTockApp.graph(ApplicationProvider.getApplicationContext())
        if (input != null) compose.waitUntil(5_000) { input.isOpen }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun text(tag: String): String? = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    private fun play(input: FakeAudioInput, hz: Double, hops: Int = 30) {
        val sine = Sine(hz, 0.1, rate)
        val before = graph.tune.level.value.hop
        repeat(hops) { input.push(sine.next(Hop.frames(rate))); Thread.sleep(5) }
        compose.waitUntil(5_000) { graph.tune.level.value.hop >= before + hops - 1 }
        assertEquals(0L, graph.tune.diagnostics.value.counters.droppedHops)
    }

    @Test fun `BND-06 live positive and negative fractional pitches render whole cents`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        for ((cents, expected) in listOf(3.6 to "+4", -8.6 to "−9")) {
            play(input, Pitch.hzFromMidi(69 + cents / 100, 440.0))
            compose.waitUntil(5_000) { text("readout-cents") == expected }
            compose.onNodeWithTag("readout-cents", true).assertTextEquals(expected)
            // Frequency retains its own decimal precision; only cents are integral.
            assertTrue(text("readout-hz").orEmpty(), text("readout-hz").orEmpty().matches(Regex("[0-9]+\\.[0-9] Hz")))
        }
    }

    @Test fun `BND-06 ears first reveal and last note round a fractional held mean`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input)
        click("needle-1")
        play(input, Pitch.hzFromMidi(69.036, 440.0))
        compose.onNodeWithTag("readout-cents", true).assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        repeat(30) { input.push(FloatArray(Hop.frames(rate))); Thread.sleep(5) }
        val until = System.nanoTime() + 5_000_000_000L
        while (text("readout-cents") != "+4") {
            assertTrue("whole-cent reveal did not arrive: ${text("readout-cents")}", System.nanoTime() < until)
            Thread.sleep(10)
            compose.mainClock.advanceTimeByFrame()
        }
        compose.onNodeWithTag("readout-cents", true).assertTextEquals("+4")
        compose.mainClock.advanceTimeBy(2_200)
        compose.waitForIdle()
        assertTrue(text("last-note").orEmpty(), text("last-note").orEmpty().startsWith("A4 +4 · "))
    }

    @Test fun `BND-06 table rows and both detail series round fractional statistics`() {
        launch()
        var hop = 0L
        fun add(cents: Double) = runBlocking {
            repeat(4) { index ->
                graph.history.accept(Reading(hop++, 69, Pitch.hzFromMidi(69 + cents / 100, 440.0), cents, 440.0, 0), index == 0)
            }
            graph.history.flushAndWait()
        }
        add(3.6)
        add(3.6)
        runBlocking {
            graph.history.saveSession(graph.history.currentSessionId()!!, "Earlier", "")
            graph.history.newSession()
        }
        add(-8.6)
        add(-4.2)
        compose.onNodeWithTag("tabs-3").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("table-note-69").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("table-note-69")
            .assertContentDescriptionEquals("A4, mean minus 6 cents, spread 2 cents, 2 attacks, 8 readings")
        compose.onNode(hasText("-6") and hasAnyAncestor(hasTestTag("table-note-69")), true).assertExists()
        click("table-note-69")
        compose.onNode(hasTestTag("table-stats-selected") and hasAnyDescendant(hasText("Mean -6 cents · Spread 2 cents"))).assertExists()
        compose.onNode(hasTestTag("table-stats-selected") and hasAnyDescendant(hasText("Attack landing -6 cents"))).assertExists()
        compose.onNode(hasTestTag("table-stats-history") and hasAnyDescendant(hasText("Mean -1 cents · Spread 5 cents"))).assertExists()
        compose.onNode(hasTestTag("table-stats-history") and hasAnyDescendant(hasText("Attack landing -1 cents"))).assertExists()
    }

    @Test fun `A4W-01 Tune and Settings step every integer and disable both endpoints`() {
        launch()
        assertEquals(440.0, graph.prefs.prefs.value.a4Hz, 0.0)
        fun exercise(tag: String) {
            var hz = graph.prefs.prefs.value.a4Hz.toInt()
            fun value() {
                compose.onNodeWithTag("$tag-value", true).assertTextEquals("$hz Hz")
                assertEquals(hz.toDouble(), graph.prefs.prefs.value.a4Hz, 0.0)
            }
            value()
            while (hz > 415) { click("$tag-lower"); hz--; value() }
            compose.onNodeWithTag("$tag-lower").assertIsNotEnabled()
            compose.onNodeWithTag("$tag-raise").assertIsEnabled()
            while (hz < 466) { click("$tag-raise"); hz++; value() }
            compose.onNodeWithTag("$tag-raise").assertIsNotEnabled()
            compose.onNodeWithTag("$tag-lower").assertIsEnabled()
            click("$tag-lower"); hz--; value()
            click("$tag-raise"); hz++; value()
        }
        exercise("a4")
        compose.onNodeWithTag("open-settings").performClick()
        exercise("settings-a4")
    }

    @Test fun `A4W-01 415 Hz reads zero and each stepped reference labels actual accepted runs`() {
        val input = FakeAudioInput(sampleRate = rate)
        launch(input, a4 = 415.0)
        for ((index, hz) in listOf(415.0, 416.0, 415.0).withIndex()) {
            if (index > 0) click(if (hz == 416.0) "a4-raise" else "a4-lower")
            play(input, hz)
            compose.waitUntil(5_000) { text("readout-hz") == "${hz.toInt()}.0 Hz" && text("readout-cents") == "+0" }
            compose.onNodeWithTag("readout-letter", true).assertTextEquals("A")
            compose.onNodeWithTag("readout-octave", true).assertTextEquals("4")
            runBlocking {
                graph.history.flushAndWait()
                val session = graph.history.sessions().single()
                assertEquals("a reading-driven run for reference change $index", index + 1, session.runs)
                val rows = graph.history.summaries(Scope.CurrentSession)
                assertTrue("accepted readings at $hz", rows.any { it.label.a4Hz == hz && it.n > 0 })
                assertTrue(rows.all { it.midiSounding == 69 && kotlin.math.abs(it.meanCents) < 0.01 })
                assertEquals(if (index == 0) setOf(415.0) else setOf(415.0, 416.0), rows.map { it.label.a4Hz }.toSet())
            }
        }
    }
}
