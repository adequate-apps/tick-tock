// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.design.components.Field
import ticktock.design.components.GlText
import ticktock.design.components.Pane
import ticktock.design.components.StatusBar
import ticktock.design.components.TitleBar

/** The frame components render under Robolectric and expose their test tags. */
@RunWith(AndroidJUnit4::class)
class FrameComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `title bar shows title and state under its tag`() {
        compose.setContent { GridlineTheme { TitleBar(title = "Tune", state = "Listening") } }
        compose.onNodeWithTag("titlebar-title", useUnmergedTree = true).assertTextEquals("Tune")
        compose.onNodeWithTag("titlebar-state", useUnmergedTree = true).assertTextEquals("Listening")
    }

    @Test
    fun `a pane's strip is tagged apart from the app strip`() {
        compose.setContent {
            GridlineTheme {
                TitleBar(title = "App")
                Pane(title = "Time") { Field { GlText("Ready.") } }
            }
        }
        compose.onNodeWithTag("titlebar-title", useUnmergedTree = true).assertTextEquals("App")
        compose.onNodeWithTag("pane-titlebar-title", useUnmergedTree = true).assertTextEquals("Time")
    }

    @Test
    fun `status bar renders one segment per sentence`() {
        compose.setContent { GridlineTheme { StatusBar(segments = listOf("Listening", "Output open")) } }
        compose.onNodeWithTag("statusbar").assertIsDisplayed()
        compose.onNodeWithTag("statusbar-0", useUnmergedTree = true).assertTextEquals("Listening")
        compose.onNodeWithTag("statusbar-1", useUnmergedTree = true).assertTextEquals("Output open")
    }
}
