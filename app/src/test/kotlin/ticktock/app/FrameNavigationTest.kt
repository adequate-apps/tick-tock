// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tabs, Settings and the back gesture: the frame's whole navigation model. */
@RunWith(AndroidJUnit4::class)
class FrameNavigationTest {
    @get:Rule(order = 0)
    val firstRunDone = SkipFirstRunRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `the four tabs are present and Tune is selected first`() {
        compose.onNodeWithTag("tabs-0").assertIsSelected()
        for (i in 1..3) compose.onNodeWithTag("tabs-$i").assertIsDisplayed()
    }

    @Test
    fun `tapping a tab shows its panes`() {
        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("tabs-1").assertIsSelected()
        compose.onNodeWithTag("drone-readout", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithTag("tabs-2").performClick()
        compose.onNodeWithTag("metro-toggle").assertTextEquals("Start")

        compose.onNodeWithTag("tabs-3").performClick()
        compose.onNodeWithTag("table-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `settings opens over the tabs and back closes it`() {
        compose.onNodeWithTag("tabs-3").performClick()
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("titlebar-title", useUnmergedTree = true).assertTextEquals("Settings")
        compose.onNodeWithTag("theme-3").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        // The tab that was open is still open underneath.
        compose.onNodeWithTag("tabs-3").assertIsSelected()
        compose.onNodeWithTag("table-empty", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the system back gesture returns to Tune from another tab`() {
        compose.onNodeWithTag("tabs-1").performClick()
        // The handler's enabled flag follows the tab through recomposition; let it settle.
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("tabs-0").assertIsSelected()
        compose.onNodeWithTag("readout").assertIsDisplayed()
    }
}
