// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.app.debug.InputSeam
import ticktock.app.output.TestOutputConnection
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.core.prefs.Theme
import kotlin.math.pow

/** The real detector opens the notice; only its stable cause and action enter the golden. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sw360dp-w360dp-h800dp-xhdpi")
class RangeHintScreenshotTest(private val scale: Float, private val theme: Theme, private val width: Int) {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private lateinit var input: FakeAudioInput
    private var deliveredHops = 0L

    @After fun close() {
        scenario?.close()
        InputSeam.override = null
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun `HNT-01 HNT-08 notice fits and its picker returns to Tune across recreation`() {
        RuntimeEnvironment.setQualifiers("sw${width}dp-w${width}dp-h800dp-xhdpi")
        RuntimeEnvironment.setFontScale(scale)
        graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.output.makeConnection = { TestOutputConnection() }
        graph.prefs.update { it.copy(firstRunComplete = true, instrumentKey = "eb_alto",
            theme = theme, capturePaused = true, a4Hz = 440.0) }
        input = FakeAudioInput(RATE)
        InputSeam.override = input
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { input.isOpen }
        compose.waitForIdle()

        trigger(40, 42, 44)
        assertNotice("Below alto range")
        golden("below-alto")

        // The longest mapped instrument name also has to fit a narrow, enlarged notice.
        if (width == 360 && scale == 2f) {
            compose.runOnIdle { graph.prefs.update { it.copy(instrumentKey = "eb_contrabass") } }
            compose.waitForIdle()
            compose.onNodeWithTag("range-hint").assertDoesNotExist()
            feed(null, 12)
            trigger(95, 97, 99)
            assertNotice("Above contrabass sax range")
            golden("above-contrabass")
        }

        // Pointer dispatch over the decorative chevron must still activate the key.
        val action = compose.onNodeWithTag("range-hint-instrument").performScrollTo()
        val density = action.fetchSemanticsNode().layoutInfo.density.density
        action.performTouchInput { click(Offset(center.x * 2f - 20f * density, center.y)) }
        compose.onNodeWithTag("instruments-pane").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        assertTune()
        compose.onNodeWithTag("range-hint-instrument").performScrollTo().performClick()
        compose.onNodeWithTag("instruments-pane").assertIsDisplayed()
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("instruments-pane").assertIsDisplayed()

        // Each return path runs in all four themes and both device widths.
        when (scale) {
            1f -> scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            1.5f -> compose.onNodeWithTag("back").performClick()
            else -> {
                compose.onNodeWithTag("instrument-bb_tenor").performScrollTo().performClick()
                assertEquals("bb_tenor", graph.prefs.prefs.value.instrumentKey)
            }
        }
        assertTune()
        assertTrue("the microphone reopens after recreation", input.isOpen)
        assertTrue("the paused capture preference survives navigation", graph.prefs.prefs.value.capturePaused)
    }

    private fun assertTune() {
        compose.waitForIdle()
        compose.onNodeWithTag("tune").assertExists()
        compose.onNodeWithTag("instruments-pane").assertDoesNotExist()
        compose.onNodeWithTag("open-settings").assertIsDisplayed()
    }

    private fun trigger(vararg notes: Int) {
        notes.forEachIndexed { index, midi ->
            if (index > 0) feed(null, 12)
            val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            feed(Sine(hz, 0.1, RATE), 24)
            compose.waitUntil(5_000) { graph.tune.state.value.readout.midi == midi }
            if (index < 2) compose.onNodeWithTag("range-hint").assertDoesNotExist()
        }
    }

    /** A production analysis publication acknowledges each hop; no elapsed-time sleeps. */
    private fun feed(sine: Sine?, hops: Int) {
        repeat(hops) {
            val frames = Hop.frames(RATE)
            assertTrue("input remains open", input.push(sine?.next(frames) ?: FloatArray(frames)))
            deliveredHops++
            runBlocking {
                withTimeout(5_000) { graph.tune.level.first { it.hop >= deliveredHops - 1 } }
            }
            assertEquals(deliveredHops - 1, graph.tune.level.value.hop)
        }
        compose.waitForIdle()
        assertEquals(0L, graph.tune.diagnostics.value.counters.droppedHops)
        assertEquals(0L, graph.tune.diagnostics.value.counters.analysisErrors)
        assertEquals(0, graph.history.rejected)
    }

    private fun assertNotice(cause: String) {
        compose.onNodeWithTag("range-hint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("range-hint-text", true).assertTextEquals(cause).assertIsDisplayed()
        val action = compose.onNodeWithTag("range-hint-instrument")
            .assertHasClickAction().assertTextEquals("Instrument").assertIsDisplayed().fetchSemanticsNode()
        assertEquals(Role.Button, action.config.getOrNull(SemanticsProperties.Role))
        val density = action.layoutInfo.density.density
        assertTrue("Instrument target width is at least 48 dp", action.boundsInRoot.width / density >= 48f)
        assertTrue("Instrument target height is at least 48 dp", action.boundsInRoot.height / density >= 48f)

        val notice = compose.onNodeWithTag("range-hint", true).fetchSemanticsNode()
        val nodes = listOf(notice) + notice.descendants()
        val textNodes = nodes.filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        assertEquals("cause and action remain separate readable facts", 2, textNodes.size)
        assertEquals("only Instrument is actionable", 1,
            nodes.count { it.config.getOrNull(SemanticsActions.OnClick) != null })
        for (node in nodes) {
            assertNull("the hint does not announce each detector hop", node.config.getOrNull(SemanticsProperties.LiveRegion))
            assertNull("decorative symbols add no redundant spoken label", node.config.getOrNull(SemanticsProperties.ContentDescription))
        }
        var ancestor = notice.parent
        while (ancestor != null) {
            assertNull("the hint has no live-region ancestor", ancestor.config.getOrNull(SemanticsProperties.LiveRegion))
            ancestor = ancestor.parent
        }
        for (node in textNodes) {
            val layout = node.textLayout()
            val words = layout.layoutInput.text.text
            assertFalse("$words overflows vertically", layout.didOverflowHeight)
            // Paragraph width can exceed the intrinsic text box; check drawn ink below.
            for (line in 0 until layout.lineCount) {
                assertFalse("$words is ellipsized", layout.isLineEllipsized(line))
                assertTrue("$words extends beyond its text box",
                    layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
            }
            assertEquals("all characters remain visible", words.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            assertTrue("$words remains inside the notice", node.boundsInRoot.left >= notice.boundsInRoot.left &&
                node.boundsInRoot.right <= notice.boundsInRoot.right &&
                node.boundsInRoot.top >= notice.boundsInRoot.top && node.boundsInRoot.bottom <= notice.boundsInRoot.bottom)
            if (words == "Instrument") {
                assertEquals("the action label remains on one line", 1, layout.lineCount)
                val inkWidth = layout.getLineRight(0) - layout.getLineLeft(0)
                val inkRight = node.boundsInRoot.center.x + inkWidth / 2f
                assertTrue("the action label leaves room for its chevron",
                    inkRight <= action.boundsInRoot.right - 32f * density + 1f)
            }
        }
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue("the full notice is reachable in the viewport", notice.boundsInRoot.top >= frame.top &&
            notice.boundsInRoot.bottom <= frame.bottom)
    }

    private fun golden(name: String) = compose.onNodeWithTag("range-hint").captureRoboImage(
        "src/test/screenshots/hint-$name-${width}x800-fs$scale-${theme.name.lowercase()}.png")

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertEquals("text exposes its measured layout", 1, results.size)
        return results.single()
    }

    companion object {
        private const val RATE = 48_000

        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "fs{0}-{1}-sw{2}")
        fun cases(): List<Array<Any>> = Theme.entries.flatMap { theme ->
            listOf(1f, 1.5f, 2f).flatMap { scale ->
                listOf(360, 600).map { width -> arrayOf<Any>(scale, theme, width) }
            }
        }
    }
}
