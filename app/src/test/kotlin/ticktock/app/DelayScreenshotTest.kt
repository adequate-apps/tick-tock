// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.app.output.OutputSession
import ticktock.app.output.TestOutputConnection
import ticktock.app.screens.DelayDialog
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.prefs.Prefs
import ticktock.design.GridlineTheme
import ticktock.design.GridlineThemes
import java.time.Duration

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class DelayScreenshotTest(private val scale: Float, private val pit: Boolean) {
    @get:Rule val compose = createComposeRule()
    private var now = 1_000_000_000L
    private var prefs = Prefs()
    private val link = TestOutputConnection().apply { openInfo = TestOutputConnection.INFO.copy(wireless = true) }
    private val output = OutputSession({ prefs }, { prefs = it(prefs) }, { link }, nanoTime = { now })
    @After fun close() { output.close(); RuntimeEnvironment.setFontScale(1f) }
    @Test fun `Bluetooth progress result and retry remain readable at large text`() {
        RuntimeEnvironment.setFontScale(scale)
        val suffix = if (pit) "-pit" else ""
        compose.setContent { GridlineTheme(if (pit) GridlineThemes.pit else GridlineThemes.gridline) { DelayDialog(output) } }
        compose.runOnIdle { output.startMetro(); output.beginCalibration() }
        val generation = link.sent.filterIsInstance<OutputCommand.MetroStart>().last().setup.generation
        repeat(8) { index ->
            compose.runOnIdle {
                now = 1_000_000_000L + index * 600_000_000L
                link.events.tryEmit(OutputEvent.Beat(index = index.toLong(), beatInBar = index % 4, frame = index * 28_800L, nanos = now, generation = generation))
            }
            compose.runOnIdle { now += 200_000_000L }
            compose.onNodeWithTag("delay-tap").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            if (index == 4) {
                compose.waitForIdle()
                compose.onNodeWithTag("delay-dialog").captureRoboImage("src/test/screenshots/delay-progress-fs$scale$suffix.png")
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        compose.onNodeWithTag("delay-use").performScrollTo().assertTextEquals("Use 200 ms")
        compose.onNodeWithTag("delay-dialog").captureRoboImage("src/test/screenshots/delay-result-fs$scale$suffix.png")
        compose.onNodeWithTag("delay-again").performScrollTo().performClick()
        assertEquals(0, output.calibration.value?.taps)
        compose.onNodeWithTag("delay-tap").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("delay-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("delay-dialog").assertDoesNotExist()
        assertNull(prefs.bluetoothVisualOffsetMs)
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "fs{0}-pit{1}")
        fun cases(): List<Array<Any>> = listOf(1f, 2f).flatMap { scale -> listOf(false, true).map { pit -> arrayOf<Any>(scale, pit) } }
    }
}
