// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import ticktock.app.screens.TableHistogram
import ticktock.data.history.NoteSummary
import ticktock.data.history.RunLabel
import ticktock.design.GridlineTheme
import ticktock.design.GridlineThemes

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableHistogramTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `spoken comparison exists only for matching note and stored label`() {
        val note = NoteSummary(RunLabel("flute_c", 0, 440.0, ""), 69, 10, 0.0, 0.0, 1, 0.0, IntArray(101).apply { this[50] = 10 })
        val history = mutableStateOf(listOf(note.copy(midiSounding = 70)))
        compose.setContent { GridlineTheme(GridlineThemes.gridline) { TableHistogram(note, history.value, false, "Session") } }
        compose.onNodeWithContentDescription("Session, share of readings, minus 50 to plus 50 cents").assertExists()
        compose.runOnIdle { history.value = listOf(note.copy(label = note.label.copy(a4Hz = 442.0))) }
        compose.onNodeWithContentDescription("Session, share of readings, minus 50 to plus 50 cents").assertExists()
        compose.runOnIdle { history.value = listOf(note) }
        compose.onNodeWithContentDescription("Session, all history overlay, share of readings, minus 50 to plus 50 cents").assertExists()
    }
}
