// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import ticktock.design.plots.Trace

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TracePlotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a singleton reading and an isolated reading between gaps both draw a visible point`() {
        var points by mutableStateOf(floatArrayOf(Float.NaN))
        compose.setContent {
            GridlineTheme {
                Trace({ points }, inTuneMax = 5f, closeMax = 12f,
                    timeLabels = listOf(0f to "attack", 1f to "end"), description = "Pitch trace, last note",
                    modifier = Modifier.width(300.dp))
            }
        }
        val plot = compose.onNodeWithTag("trace")
        val empty = plot.captureToImage().toPixelMap()
        compose.runOnIdle { points = floatArrayOf(20f) }
        val single = plot.captureToImage().toPixelMap()
        assertEquals(empty.width, single.width)
        assertEquals(empty.height, single.height)
        var marks = 0
        for (y in 0 until single.height) for (x in 0 until single.width) {
            if (single[x, y] != empty[x, y]) marks++
        }
        assertTrue("one accepted hop leaves a visible mark", marks > 0)
        plot.captureRoboImage("src/test/screenshots/trace-single-hop.png")
        compose.runOnIdle { points = floatArrayOf(Float.NaN, 20f, Float.NaN) }
        val isolated = plot.captureToImage().toPixelMap()
        for (y in 0 until single.height) for (x in 0 until single.width) {
            assertEquals("isolated point at $x,$y", single[x, y], isolated[x, y])
        }
    }
}
