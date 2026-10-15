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
 * Gridline goldens of the frame on a 360 x 640 dp phone at font scales 1.0,
 * 1.3, 1.5 and 2.0 (A11Y-02). The goldens live in app/src/test/screenshots and are
 * written by `:app:recordRoborazziDebug`; `:app:verifyRoborazziDebug` fails
 * on any pixel change. Under a plain test run the capture is a no-op and the
 * layout assertions still have to hold: every label is displayed, and the
 * tabs wrap into rows rather than clip.
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
        skipFirstRun()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun golden(name: String) {
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun assertFrameReadable() {
        compose.onNodeWithTag("titlebar-title", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("open-settings").assertIsDisplayed()
        // Every tab is on screen at every font scale: the keys wrap into rows
        // rather than scroll a label out of view.
        for (i in 0..3) compose.onNodeWithTag("tabs-$i").assertIsDisplayed()
        compose.onNodeWithTag("statusbar").assertIsDisplayed()
    }

    @Test
    fun `tune at font scale 1_0`() {
        launch(1f)
        assertFrameReadable()
        compose.onNodeWithTag("readout").assertIsDisplayed()
        golden("frame-tune-360x640-fs1.0")
    }

    @Test
    fun `tune at font scale 1_3`() {
        launch(1.3f)
        assertFrameReadable()
        compose.onNodeWithTag("readout").performScrollTo().assertIsDisplayed()
        golden("frame-tune-360x640-fs1.3")
    }

    @Test
    fun `tune at font scale 1_5`() {
        launch(1.5f)
        assertFrameReadable()
        compose.onNodeWithTag("readout").performScrollTo().assertIsDisplayed()
        golden("frame-tune-360x640-fs1.5")
    }

    @Test
    fun `tune at font scale 2_0`() {
        launch(2f)
        assertFrameReadable()
        compose.onNodeWithTag("readout").performScrollTo().assertIsDisplayed()
        golden("frame-tune-360x640-fs2.0")
    }

    @Test
    fun `drone at font scale 1_0`() {
        launch(1f)
        compose.onNodeWithTag("tabs-1").performClick()
        assertFrameReadable()
        compose.onNodeWithTag("drone-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-drone-360x640-fs1.0")
    }

    @Test
    fun `drone at font scale 1_3`() {
        launch(1.3f)
        compose.onNodeWithTag("tabs-1").performClick()
        assertFrameReadable()
        compose.onNodeWithTag("drone-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-drone-360x640-fs1.3")
    }

    @Test
    fun `drone at font scale 1_5`() {
        launch(1.5f)
        compose.onNodeWithTag("tabs-1").performClick()
        assertFrameReadable()
        compose.onNodeWithTag("drone-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-drone-360x640-fs1.5")
    }

    @Test
    fun `drone at font scale 2_0`() {
        launch(2f)
        compose.onNodeWithTag("tabs-1").performClick()
        assertFrameReadable()
        compose.onNodeWithTag("drone-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-drone-360x640-fs2.0")
    }

    @Test
    fun `metronome at font scale 1_0`() {
        launch(1f)
        compose.onNodeWithTag("tabs-2").performClick()
        assertFrameReadable()
        compose.onNodeWithTag("metronome-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-metronome-360x640-fs1.0")
    }

    @Test
    fun `metronome at font scale 1_3`() {
        launch(1.3f)
        compose.onNodeWithTag("tabs-2").performClick()
        assertFrameReadable()
        compose.onNodeWithTag("metronome-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-metronome-360x640-fs1.3")
    }

    @Test
    fun `metronome at font scale 1_5`() {
        launch(1.5f)
        compose.onNodeWithTag("tabs-2").performClick()
        assertFrameReadable()
        compose.onNodeWithTag("metronome-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-metronome-360x640-fs1.5")
    }

    @Test
    fun `metronome at font scale 2_0`() {
        launch(2f)
        compose.onNodeWithTag("tabs-2").performClick()
        assertFrameReadable()
        compose.onNodeWithTag("metronome-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        golden("frame-metronome-360x640-fs2.0")
    }

    @Test
    fun `settings at font scale 1_0`() {
        launch(1f)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("back").assertIsDisplayed()
        compose.onNodeWithTag("theme-3").assertIsDisplayed()
        golden("frame-settings-360x640-fs1.0")
    }

    @Test
    fun `settings at font scale 1_3`() {
        launch(1.3f)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("back").assertIsDisplayed()
        compose.onNodeWithTag("theme-3").performScrollTo().assertIsDisplayed()
        golden("frame-settings-360x640-fs1.3")
    }

    @Test
    fun `settings at font scale 1_5`() {
        launch(1.5f)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("back").assertIsDisplayed()
        compose.onNodeWithTag("theme-3").performScrollTo().assertIsDisplayed()
        golden("frame-settings-360x640-fs1.5")
    }

    @Test
    fun `settings at font scale 2_0`() {
        launch(2f)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("back").assertIsDisplayed()
        compose.onNodeWithTag("theme-3").performScrollTo().assertIsDisplayed()
        golden("frame-settings-360x640-fs2.0")
    }
}
