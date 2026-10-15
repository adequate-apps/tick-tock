// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import ticktock.design.components.GlText
import ticktock.design.components.HistoryRow

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `history note names use readable accent ink on the dark field`() {
        compose.setContent { GridlineTheme(palette = GridlineThemes.dark) {
            HistoryRow("B♭4", "38 readings", "B flat 4", false, {}, Modifier.width(300.dp),
                mean = { GlText("+4") }, chart = {})
        } }
        val pixels = compose.onNodeWithText("B♭4", useUnmergedTree = true).captureToImage().toPixelMap()
        var inkPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y] == GridlineThemes.dark.accentText) inkPixels++
        }
        assertTrue("note text must use the theme's foreground accent", inkPixels > 0)
    }
    @Test fun `history curves use the visible foreground accent on the dark field`() {
        compose.setContent { GridlineTheme(palette = GridlineThemes.dark) {
            ticktock.design.plots.Histogram(IntArray(101).apply { this[54] = 8 }, inTuneMax = 5f, closeMax = 12f,
                description = "Eight readings", modifier = Modifier.width(300.dp))
        } }
        val pixels = compose.onNodeWithTag("histogram").captureToImage().toPixelMap()
        var inkPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y] == GridlineThemes.dark.accentText) inkPixels++
        }
        assertTrue("curve bars must use the theme's foreground accent", inkPixels > 0)
    }

}
