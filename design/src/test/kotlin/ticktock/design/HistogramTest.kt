// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import ticktock.design.plots.Histogram

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistogramTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `session and history retain pitch position and comparable density despite sample counts`() {
        compose.setContent { GridlineTheme {
            Histogram(IntArray(101).apply { this[70] = 10 }, IntArray(101).apply { this[30] = 10_000 },
                5f, 12f, "Session over history", Modifier.width(300.dp))
        } }
        compose.onNodeWithTag("histogram").assertContentDescriptionEquals("Session over history")
        val pixels = compose.onNodeWithTag("histogram").captureToImage().toPixelMap()
        fun bounds(color: Color, region: IntRange): List<Int> {
            val points = buildList { for (y in 0 until pixels.height) for (x in region) if (pixels[x, y] == color) add(x to y) }
            assertTrue(points.isNotEmpty())
            return listOf(points.minOf { it.first }, points.maxOf { it.first }, points.minOf { it.second }, points.maxOf { it.second })
        }
        val current = bounds(GridlineThemes.gridline.active, 0 until pixels.width)
        val previous = bounds(GridlineThemes.gridline.borderLight, 0 until (pixels.width * 0.45).toInt())
        assertEquals(0.70, (current[0] + current[1]) / 2.0 / pixels.width, 0.02)
        assertEquals(0.30, (previous[0] + previous[1]) / 2.0 / pixels.width, 0.02)
        assertTrue(current[3] - current[2] > pixels.height * 0.7)
        assertEquals(previous[3] - previous[2], current[3] - current[2])
    }

    @Test fun `compact curves crop the wider tails and dimmed rows keep a visible distribution`() {
        val compact = mutableStateOf(true)
        val dimmed = mutableStateOf(false)
        compose.setContent { GridlineTheme {
            Histogram(IntArray(101).apply { this[90] = 4 }, inTuneMax = 5f, closeMax = 12f,
                description = "Four readings", modifier = Modifier.width(300.dp), compact = compact.value, dimmed = dimmed.value)
        } }
        fun count(color: Color): Int {
            val pixels = compose.onNodeWithTag("histogram").captureToImage().toPixelMap()
            var count = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y] == color) count++
            return count
        }
        assertEquals(0, count(GridlineThemes.gridline.active))
        compose.runOnIdle { compact.value = false }
        assertTrue(count(GridlineThemes.gridline.active) > 0)
        compose.runOnIdle { dimmed.value = true }
        assertEquals(0, count(GridlineThemes.gridline.active))
        assertTrue(count(GridlineThemes.gridline.textDisabled) > 0)
    }
}
