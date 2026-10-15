// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.design.components.Pad
import ticktock.design.components.PadGrid

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h2000dp-hdpi")
class PadsTest(private val fontScale: Float, private val widthDp: Int) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `reference pads keep names and marks whole and expose a full-size selection target`() {
        val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GridlineTheme {
                    var selected by remember { mutableIntStateOf(0) }
                    PadGrid(names.mapIndexed { i, name -> Pad(name, "B♭3", listOf("ensemble", "check"), selected = i == selected) },
                        { selected = it; clicks++ }, Modifier.width(widthDp.dp), tag = "notes")
                }
            }
        }
        for (i in names.indices) assertReadable("notes-$i")
        compose.onNodeWithTag("notes-1").performClick().assertIsSelected()
        assertEquals(1, clicks)
        if (fontScale == 2f && widthDp == 300) compose.onNodeWithTag("notes").captureRoboImage("src/test/screenshots/note-pads-large-text.png")
    }

    @Test
    fun `ensemble pads stack when complete labels need the width`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GridlineTheme {
                    PadGrid(listOf(Pad("Concert B♭", "written F♯4"), Pad("Concert A", "written F4")), {},
                        Modifier.width(widthDp.dp), maxColumns = 2, tag = "ensemble")
                }
            }
        }
        assertReadable("ensemble-0")
        assertReadable("ensemble-1")
        if (fontScale == 2f && widthDp == 300) {
            val a = compose.onNodeWithTag("ensemble-0").fetchSemanticsNode().boundsInRoot
            val b = compose.onNodeWithTag("ensemble-1").fetchSemanticsNode().boundsInRoot
            assertTrue(b.top >= a.bottom)
            compose.onNodeWithTag("ensemble").captureRoboImage("src/test/screenshots/ensemble-pads-large-text.png")
        }
    }

    private fun assertReadable(tag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val target = GridlineMetrics.touchTarget.value * node.layoutInfo.density.density
        assertTrue(node.boundsInRoot.width >= target && node.boundsInRoot.height >= target)
        fun layouts(node: SemanticsNode): List<TextLayoutResult> {
            val result = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(result)
            node.children.forEach { result.addAll(layouts(it)) }
            return result
        }
        val text = layouts(node)
        assertTrue(text.isNotEmpty())
        for (layout in text) {
            assertEquals("${layout.layoutInput.text} at $fontScale and $widthDp", 1, layout.lineCount)
            val inkWidth = layout.getLineRight(0) - layout.getLineLeft(0)
            // Text layout rounds its box to whole pixels while glyph advances retain fractions.
            assertTrue("${layout.layoutInput.text} overflows its box", inkWidth <= layout.size.width + 1f)
            assertFalse(layout.didOverflowHeight)
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(0, visibleEnd = true))
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fs{0}-w{1}")
        fun cases(): List<Array<Any>> = listOf(1f, 1.3f, 1.5f, 2f).flatMap { scale ->
            listOf(300, 560).map { width -> arrayOf<Any>(scale, width) }
        }
    }
}
