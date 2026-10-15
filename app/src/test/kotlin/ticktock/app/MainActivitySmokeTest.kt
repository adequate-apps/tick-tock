// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
    fun `the status bar states what the microphone and output are doing`() {
        compose.onNodeWithTag("statusbar").assertIsDisplayed()
        compose.onNodeWithTag("statusbar-0", useUnmergedTree = true).assertTextEquals("Mic off")
        compose.onNodeWithTag("statusbar-1", useUnmergedTree = true).assertTextEquals("Output closed")
    }

    @Test
    fun `the work area opens on the Tune pane`() {
        compose.onNodeWithTag("tune-pane-titlebar-title", useUnmergedTree = true).assertTextEquals("Tune")
        compose.onNodeWithTag("tune-empty", useUnmergedTree = true).assertIsDisplayed()
    }
}
