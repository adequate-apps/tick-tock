// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.app.debug.InputSeam
import ticktock.core.config.PersonalConfig
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.core.ranges.UserRangeOverride
import ticktock.core.ranges.WrittenRange
import ticktock.design.GridlineThemes

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class RangeScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    @After fun close() { scenario?.close(); InputSeam.override = null }

    @Test fun `RNG-11 out of range note keeps its cents and Hz dimmed and an imported widening restores ink`() {
        val graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.prefs.update { it.copy(firstRunComplete = true, instrumentKey = "eb_alto") }
        val input = FakeAudioInput(sampleRate = 48_000)
        InputSeam.override = input
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val sine = Sine(2093.004522, 0.1, 48_000)
        repeat(18) {
            val hop = graph.tune.level.value.hop
            input.push(sine.next(Hop.frames(48_000)))
            compose.waitUntil(5_000) { graph.tune.level.value.hop > hop }
        }
        compose.onNodeWithTag("readout-cents", true).assertTextEquals("+0")
        compose.onNodeWithTag("readout-hz", true).assertTextContains("2093", substring = true)
        fun pixels(tag: String, color: androidx.compose.ui.graphics.Color): Int {
            val image = compose.onNodeWithTag(tag, true).performScrollTo().captureToImage().toPixelMap()
            var count = 0
            for (y in 0 until image.height) for (x in 0 until image.width) if (image[x, y] == color) count++
            return count
        }
        assertTrue("out of range note stays visible in disabled ink", pixels("readout-letter", GridlineThemes.gridline.textDisabled) > 0)
        assertTrue("out of range cents stay visible in disabled ink", pixels("readout-cents", GridlineThemes.gridline.textDisabled) > 0)
        compose.runOnIdle { graph.prefs.update { it.copy(personalConfig = PersonalConfig(rangeOverrides = mapOf("eb_alto" to UserRangeOverride(WrittenRange(57, 105))))) } }
        assertTrue("imported extension restores note ink", pixels("readout-letter", GridlineThemes.gridline.textBody) > 0)
        assertTrue("imported extension restores tuning colour", pixels("readout-cents", GridlineThemes.gridline.positive) > 0)
    }
}
