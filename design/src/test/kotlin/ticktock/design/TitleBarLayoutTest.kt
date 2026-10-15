// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import ticktock.design.components.TitleBar
import ticktock.design.components.TitleBarAction

/**
 * The title strip's state gives way before it crowds the title or the action.
 * Text has real widths only in native graphics mode, which these cases need.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TitleBarLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a title strip too narrow for its state drops the state and keeps the title and action`() {
        compose.setContent {
            GridlineTheme {
                Box(Modifier.width(220.dp)) {
                    TitleBar(title = "Metronome", state = "Listening") {
                        TitleBarAction(label = "Settings", onClick = {}, tag = "act")
                    }
                }
            }
        }
        compose.onNodeWithTag("titlebar-title", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("act").assertIsDisplayed()
        compose.onNodeWithTag("titlebar-state", useUnmergedTree = true).assertIsNotDisplayed()
    }

    @Test
    fun `a title strip with room keeps its state`() {
        compose.setContent {
            GridlineTheme {
                Box(Modifier.width(400.dp)) {
                    TitleBar(title = "Metronome", state = "Listening") {
                        TitleBarAction(label = "Settings", onClick = {}, tag = "act")
                    }
                }
            }
        }
        compose.onNodeWithTag("titlebar-state", useUnmergedTree = true).assertIsDisplayed()
    }
}
