// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.design.components.Button
import ticktock.design.components.Notice
import ticktock.design.components.NoticeKind
import ticktock.design.components.Segment
import ticktock.design.components.Segmented
import ticktock.design.components.SettingRow

/** The interactive Gridline controls: targets, states and semantics. */
@RunWith(AndroidJUnit4::class)
class ControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a button fires once per tap and meets the touch target`() {
        var taps = 0
        compose.setContent { GridlineTheme { Button(label = "Start", onClick = { taps++ }, tag = "start") } }
        compose.onNodeWithTag("start").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertIsEnabled()
        compose.onNodeWithTag("start").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `a disabled button ignores taps`() {
        var taps = 0
        compose.setContent { GridlineTheme { Button(label = "Save", onClick = { taps++ }, enabled = false, tag = "save") } }
        compose.onNodeWithTag("save").assertIsNotEnabled().performClick()
        assertEquals(0, taps)
    }

    @Test
    fun `segmented reports selection and switches on tap`() {
        compose.setContent {
            var selected by mutableIntStateOf(0)
            GridlineTheme {
                Segmented(
                    segments = listOf(Segment("Tune"), Segment("Table", running = true)),
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    tag = "tabs",
                )
            }
        }
        compose.onNodeWithTag("tabs-0").assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("tabs-1").assertIsNotSelected()
        compose.onNodeWithTag("running", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("tabs-1").assertIsSelected()
        compose.onNodeWithTag("tabs-0").assertIsNotSelected()
    }

    @Test
    fun `a setting row shows its label and value and takes a tap`() {
        var taps = 0
        compose.setContent { GridlineTheme { SettingRow(label = "A4", value = "440 Hz", onClick = { taps++ }, tag = "a4") } }
        compose.onNodeWithTag("a4-label", useUnmergedTree = true).assertTextEquals("A4")
        compose.onNodeWithTag("a4-value", useUnmergedTree = true).assertTextEquals("440 Hz")
        compose.onNodeWithTag("a4").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `a notice shows its sentence`() {
        compose.setContent {
            GridlineTheme { Notice(text = "Bluetooth output adds delay to the click.", kind = NoticeKind.Attention, tag = "bt") }
        }
        compose.onNodeWithTag("bt-text", useUnmergedTree = true).assertTextEquals("Bluetooth output adds delay to the click.")
    }
}
