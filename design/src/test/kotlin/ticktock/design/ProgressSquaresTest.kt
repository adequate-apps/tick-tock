// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import ticktock.design.components.ProgressSquares

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgressSquaresTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `Pit progress uses a dark face and an accent outline`() {
        compose.setContent { GridlineTheme(GridlineThemes.pit) {
            ProgressSquares(1, 1, null, "Progress", cellSize = 24.dp)
        } }
        val pixels = compose.onNodeWithTag("progress-squares").captureToImage().toPixelMap()
        assertEquals(GridlineThemes.pit.selectionBg, pixels[pixels.width / 2, pixels.height / 2])
        var accent = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y] == GridlineThemes.pit.selectionOutline) accent++
        }
        assertTrue("completed cell retains its outline", accent > 0)
    }
}
