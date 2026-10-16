// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.core.config.PersonalConfig
import ticktock.core.detect.Reading
import ticktock.core.music.Pitch
import ticktock.core.ranges.UserRangeOverride
import ticktock.core.ranges.WrittenRange
import ticktock.data.history.Scope
import ticktock.design.GridlineThemes
import ticktock.design.GridlineSpacing
import kotlin.math.roundToInt

/** Retained rows have separate keyed-border, extended-chart and low-sample roles. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class RangeTableAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private var hop = 0L
    private val colors = GridlineThemes.gridline

    @After fun close() { scenario?.close() }

    private fun launch(vararg rows: Pair<Int, Int>) {
        graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.tune.micAllowed = { false }
        graph.prefs.update { it.copy(firstRunComplete = true, instrumentKey = "eb_alto") }
        rows.forEach { (midi, count) ->
            repeat(count) { index ->
                graph.history.accept(Reading(hop++, midi, Pitch.hzFromMidi(midi + 0.04, 440.0), 4.0, 440.0, 0), index == 0)
            }
        }
        assertTrue(graph.history.flushAndWait())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("tabs-3").performClick()
        awaitRows(*rows.map { it.first }.toIntArray())
    }

    private fun awaitRows(vararg midi: Int) = compose.waitUntil(5_000) {
        midi.all { compose.onAllNodesWithTag("table-note-$it").fetchSemanticsNodes().size == 1 }
    }

    private fun row(midi: Int) = compose.onNodeWithTag("table-note-$midi")

    private fun compactChart(midi: Int): SemanticsNodeInteraction {
        row(midi).performScrollTo()
        return compose.onNode(hasTestTag("histogram") and hasAnyAncestor(hasTestTag("table-note-$midi")), useUnmergedTree = true)
    }

    private fun ink(node: SemanticsNodeInteraction, color: Color): Int {
        val pixels = node.captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y] == color) count++
        return count
    }

    private fun assertKeyedBorder(midi: Int, outside: Boolean) {
        // The range marker is a square one-unit outline, using the existing subdued
        // border token. Sample both side edges away from text and the row divider.
        val pixels = row(midi).performScrollTo().captureToImage().toPixelMap()
        for (x in listOf(0, pixels.width - 1)) {
            val actual = pixels[x, pixels.height / 2]
            if (outside) assertEquals("out-of-keyed row $midi has a subdued border at edge $x", colors.borderLight, actual)
            else assertEquals("keyed row $midi keeps its ordinary field edge at $x", colors.field, actual)
        }
    }

    @Test fun `RNG-12 played extended-only rows retain cents with a keyed border and normal chart ink`() {
        // Alto: sounding 81 is written F#6 (keyed top); 82 is written G6.
        launch(81 to 8, 82 to 8, 69 to 2)
        row(82).assertHasClickAction().assertContentDescriptionContains("mean plus 4", substring = true)
        assertEquals(18L, runBlocking { graph.history.summaries(Scope.CurrentSession) }.sumOf { it.n })
        assertTrue("extended-only note is still in range for chart judgment", ink(compactChart(82), colors.accentText) > 0)
        assertEquals("low-N row remains gray", 0, ink(compactChart(69), colors.accentText))
        assertTrue("low-N row remains visible", ink(compactChart(69), colors.textDisabled) > 0)
        assertKeyedBorder(81, outside = false)
        assertKeyedBorder(82, outside = true)
    }

    @Test fun `RNG-02 outside-extended rows remain visible with dimmed compact and detail charts`() {
        // Alto extended top is sounding 92 (written F7); 93 is outside it.
        launch(92 to 8, 93 to 8)
        assertTrue("extended endpoint retains normal chart ink", ink(compactChart(92), colors.accentText) > 0)
        row(93).assertHasClickAction().assertContentDescriptionContains("mean plus 4", substring = true)
        assertEquals("outside-extended compact chart is dimmed despite sufficient readings", 0,
            ink(compactChart(93), colors.accentText))
        assertTrue(ink(compactChart(93), colors.textDisabled) > 0)
        row(93).performScrollTo().performClick()
        val detail = compose.onNodeWithTag("histogram", useUnmergedTree = true).performScrollTo()
        assertEquals("detail chart applies the same extended judgment", 0, ink(detail, colors.accentText))
        assertTrue(ink(detail, colors.textDisabled) > 0)
        compose.onNode(hasTestTag("table-stats-selected") and hasAnyDescendant(hasText("Mean +4 cents · Spread 0 cents"))).assertExists()
        assertEquals(16L, runBlocking { graph.history.summaries(Scope.CurrentSession) }.sumOf { it.n })
    }

    @Test fun `RNG-12 TLK-03 row speech distinguishes historical keyed and extended boundaries`() {
        launch(72 to 8, 82 to 8, 93 to 8)
        // Ordinary rows keep the adopted summary byte for byte.
        row(72).assertContentDescriptionEquals("A5, mean plus 4 cents, spread 0 cents, 1 attack, 8 readings")
        row(82).assertContentDescriptionContains("Outside keyed range", substring = true)
            .assert(!hasContentDescription("Outside extended range", substring = true))
        row(93).assertContentDescriptionContains("Outside extended range", substring = true)
            .assert(!hasContentDescription("Outside keyed range", substring = true))
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("setting-instrument").performScrollTo().performClick()
        compose.onNodeWithTag("instrument-flute_c").performScrollTo().performClick()
        compose.onNodeWithTag("back").performClick()
        awaitRows(72, 82, 93)
        row(82).assertContentDescriptionContains("Outside keyed range", substring = true)
        row(93).assertContentDescriptionContains("Outside extended range", substring = true)
        compose.runOnIdle {
            graph.prefs.update { it.copy(personalConfig = PersonalConfig(rangeOverrides = mapOf(
                "eb_alto" to UserRangeOverride(WrittenRange(58, 91)),
            ))) }
        }
        row(82).assert(!hasContentDescription("Outside keyed range", substring = true))
            .assert(!hasContentDescription("Outside extended range", substring = true))
        row(93).assertContentDescriptionContains("Outside extended range", substring = true)
        assertEquals(24L, runBlocking { graph.history.summaries(Scope.CurrentSession) }.sumOf { it.n })
    }

    @Test fun `RNG-02 detail identifies an extended exclusion independently of sample count`() {
        launch(72 to 2, 82 to 8, 93 to 8, 94 to 2)
        for (midi in listOf(93, 94)) {
            row(midi).performScrollTo().performClick()
            compose.onNodeWithText("Outside extended range").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Dimmed <5 readings").assertDoesNotExist()
            compose.onNodeWithTag("table-back").performScrollTo().performClick()
        }
        // Preserve the existing fact and geometry for keyed and extended-only details.
        for (midi in listOf(72, 82)) {
            row(midi).performScrollTo().performClick()
            compose.onNodeWithText("Dimmed <5 readings").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Outside extended range").assertDoesNotExist()
            compose.onNodeWithTag("table-back").performScrollTo().performClick()
        }
        assertEquals(20L, runBlocking { graph.history.summaries(Scope.CurrentSession) }.sumOf { it.n })
    }

    @Test fun `RNG-02 detail legend swatch agrees with extended chart judgment and low counts`() {
        launch(72 to 2, 82 to 8, 93 to 8)
        for ((midi, expected) in listOf(93 to colors.textDisabled, 82 to colors.accentText, 72 to colors.textDisabled)) {
            row(midi).performScrollTo().performClick()
            assertDetailSwatch(expected)
            val chart = compose.onNodeWithTag("histogram", useUnmergedTree = true).performScrollTo()
            assertTrue("the matching chart ink remains visible", ink(chart, expected) > 0)
            if (expected == colors.textDisabled) assertEquals(0, ink(chart, colors.accentText))
            compose.onNodeWithTag("table-back").performScrollTo().performClick()
        }
    }

    private fun assertDetailSwatch(expected: Color) {
        // The Session text identifies the visible series key; the other Session is its stats heading.
        val label = compose.onNode(hasText("Session") and !hasAnyAncestor(hasTestTag("table-stats-selected")),
            useUnmergedTree = true).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val frame = compose.onNodeWithTag("frame")
        val frameBounds = frame.fetchSemanticsNode().boundsInWindow
        val pixels = frame.captureToImage().toPixelMap()
        assertEquals(frameBounds.width.roundToInt(), pixels.width)
        assertEquals(frameBounds.height.roundToInt(), pixels.height)
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        // Locate the center of the existing square swatch using its spacing tokens, not a new tag/UI.
        val x = (label.left - (GridlineSpacing.sp2.value + GridlineSpacing.sp3.value / 2) * density - frameBounds.left).roundToInt()
        val y = (label.center.y - frameBounds.top).roundToInt()
        assertTrue("series swatch sample lies in the frame", x in 0 until pixels.width && y in 0 until pixels.height)
        assertEquals("Session legend swatch must use its actual plotted ink", expected, pixels[x, y])
    }

    @Test fun `RNG-12 historical instrument and its imported override own the row border`() {
        launch(82 to 8)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("setting-instrument").performScrollTo().performClick()
        compose.onNodeWithTag("instrument-flute_c").performScrollTo().performClick()
        compose.onNodeWithTag("back").performClick()
        awaitRows(82)
        row(82).assertContentDescriptionContains("G6", substring = true)
        assertKeyedBorder(82, outside = true)
        assertTrue("historical alto G6 remains extended-only under current flute", ink(compactChart(82), colors.accentText) > 0)
        compose.runOnIdle {
            graph.prefs.update { it.copy(personalConfig = PersonalConfig(rangeOverrides = mapOf(
                "eb_alto" to UserRangeOverride(WrittenRange(58, 91)),
            ))) }
        }
        assertKeyedBorder(82, outside = false)
        val saved = runBlocking { graph.history.summaries(Scope.CurrentSession) }.single()
        assertEquals("eb_alto", saved.label.instrumentKey)
        assertEquals(-9, saved.label.transposition)
        assertEquals(8L, saved.n)
        row(82).performClick()
        compose.onNodeWithText("G6").assertExists()
    }
}
