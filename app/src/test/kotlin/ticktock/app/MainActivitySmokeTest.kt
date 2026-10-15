// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.design.GridlineSpacing

/** The empty app boots to a Gridline frame: title strip, tabs, one pane, status bar. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `the frame shows the app name in the title strip`() {
        compose.onNodeWithTag("titlebar-title", useUnmergedTree = true).assertTextEquals(AppIdentity.NAME)
    }

    @Test
    fun `the status bar says Mic off until the microphone is allowed, and nothing else`() {
        compose.onNodeWithTag("statusbar").assertIsDisplayed().assertHeightIsAtLeast(GridlineSpacing.statusBarHeight)
        compose.onNodeWithTag("statusbar-0", useUnmergedTree = true).assertTextEquals("Mic off")
        compose.onNodeWithTag("statusbar-1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the work area opens on the Tune pane`() {
        compose.onNodeWithTag("readout").assertIsDisplayed()
        compose.onNodeWithTag("cents-scale").assertIsDisplayed()
    }
}
