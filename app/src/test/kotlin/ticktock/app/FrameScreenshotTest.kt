// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gridline goldens of the frame on a 360 x 640 dp phone at font scale 1.0
 * and 2.0 (A11Y-02). The goldens live in app/src/test/screenshots and are
 * written by `:app:recordRoborazziDebug`; `:app:verifyRoborazziDebug` fails
 * on any pixel change. Under a plain test run the capture is a no-op and the
 * layout assertions still have to hold: every label is displayed, and a tab
 * that no longer fits is reachable by scrolling rather than clipped.
 *
 * NATIVE graphics mode is what makes this real rendering; in legacy mode
 * every draw call is a no-op and the image would be blank.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class FrameScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    /** The font scale must be in place before the activity inflates. */
    private fun launch(fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun golden(name: String) {
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun assertFrameReadable() {
        compose.onNodeWithTag("titlebar-title", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("open-settings").assertIsDisplayed()
        // A tab that no longer fits must be reachable by scrolling; then return the
        // row to its origin so the golden shows what the player sees first.
        compose.onNodeWithTag("tabs-2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tabs-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("statusbar-0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("statusbar-1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `tune at font scale 1_0`() {
        launch(1f)
        assertFrameReadable()
        compose.onNodeWithTag("tune-empty", useUnmergedTree = true).assertIsDisplayed()
        golden("frame-tune-360x640-fs1.0")
    }

    @Test
    fun `tune at font scale 2_0`() {
        launch(2f)
        assertFrameReadable()
        compose.onNodeWithTag("tune-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-tune-360x640-fs2.0")
    }

    @Test
    fun `drone and metronome at font scale 1_0`() {
        launch(1f)
        compose.onNodeWithTag("tabs-1").performClick()
        assertFrameReadable()
        compose.onNodeWithTag("metronome-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-drone-metronome-360x640-fs1.0")
    }

    @Test
    fun `drone and metronome at font scale 2_0`() {
        launch(2f)
        compose.onNodeWithTag("tabs-1").performClick()
        assertFrameReadable()
        compose.onNodeWithTag("metronome-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-drone-metronome-360x640-fs2.0")
    }

    @Test
    fun `settings at font scale 1_0`() {
        launch(1f)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("back").assertIsDisplayed()
        compose.onNodeWithTag("settings-empty", useUnmergedTree = true).assertIsDisplayed()
        golden("frame-settings-360x640-fs1.0")
    }

    @Test
    fun `settings at font scale 2_0`() {
        launch(2f)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("back").assertIsDisplayed()
        compose.onNodeWithTag("settings-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-settings-360x640-fs2.0")
    }
}
