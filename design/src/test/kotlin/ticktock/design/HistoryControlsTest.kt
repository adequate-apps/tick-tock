// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.design.components.GlText
import ticktock.design.components.HistoryRow
import ticktock.design.components.TextField
import ticktock.design.plots.Histogram

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h2000dp-hdpi")
class HistoryControlsTest(private val fontScale: Float, private val width: Int) {
    @get:Rule val compose = createComposeRule()

    @Test fun `history rows expose one full spoken action and fields retain multiline drafts`() {
        var opened = 0
        val draft = mutableStateOf("")
        val description = "B flat 4, mean plus 4, spread 6, 3 attacks, 38 readings"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GridlineTheme {
                    Column(Modifier.width(width.dp)) {
                        HistoryRow("B♭4", "3\n38", description, false, { opened++ },
                            mean = { GlText("+4", style = GridlineType.value) },
                            chart = { Histogram(IntArray(101).apply { this[54] = 38 }, inTuneMax = 5f, closeMax = 12f,
                                description = "", compact = true) })
                        TextField("Notes", draft.value, { draft.value = it }, multiline = true)
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("history-row").assertContentDescriptionEquals(description).assertHasClickAction()
        row.performClick()
        assertEquals(1, opened)
        row.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        assertEquals(2, opened)
        for (text in listOf("B♭4", "+4", "3\n38")) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            for (layout in layouts) {
                assertEquals(if (text.contains('\n')) 2 else 1, layout.lineCount)
                assertFalse(layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line))
                    assertTrue(layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
                }
            }
        }
        val bounds = row.fetchSemanticsNode().boundsInRoot
        val density = row.fetchSemanticsNode().layoutInfo.density.density
        assertTrue(bounds.height >= 48 * density)
        assertTrue(bounds.width <= width * density + 1)
        compose.onNodeWithTag("text-entry").performTextInput("Cold start\nNew reed")
        assertEquals("Cold start\nNew reed", draft.value)
        compose.onNodeWithTag("text-entry").assertContentDescriptionEquals("Notes")
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "fs{0}-w{1}")
        fun cases(): List<Array<Any>> = listOf(1f, 1.3f, 1.5f, 2f).flatMap { scale -> listOf(300, 560).map { arrayOf<Any>(scale, it) } }
    }
}
