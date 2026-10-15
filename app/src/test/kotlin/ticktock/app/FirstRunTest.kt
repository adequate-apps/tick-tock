// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** First run (mockups 10a, 10b): two steps, then the tabs, and never again. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class FirstRunTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
    }

    @Test
    fun `a fresh install asks for the microphone, then the instrument, then opens on the tabs and stays there`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("first-run-mic").assertIsDisplayed()
        compose.onNodeWithTag("open-settings").assertDoesNotExist()
        compose.onNodeWithTag("tabs").assertDoesNotExist()
        compose.onRoot().captureRoboImage("src/test/screenshots/first-run-microphone-360x640.png")

        compose.onNodeWithTag("first-run-later").performClick()
        compose.onNodeWithTag("first-run-instrument").assertIsDisplayed()
        compose.onNodeWithTag("first-run-bb_tenor").assertIsSelected()
        compose.onNodeWithTag("first-run-eb_alto").performClick()
        compose.onNodeWithTag("first-run-eb_alto").assertIsSelected()
        compose.onRoot().captureRoboImage("src/test/screenshots/first-run-instrument-360x640.png")

        compose.onNodeWithTag("first-run-start").performScrollTo().performClick()
        compose.onNodeWithTag("tabs").assertIsDisplayed()
        compose.onNodeWithTag("readout").assertIsDisplayed()
        val graph = TickTockApp.graph(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        assertTrue(graph.prefs.prefs.value.firstRunComplete)
        assertEquals("eb_alto", graph.prefs.prefs.value.instrumentKey)

        scenario?.recreate()
        compose.onNodeWithTag("tabs").assertIsDisplayed()
    }

    @Test
    fun `All instruments opens the full list and a choice comes back to the step`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("first-run-later").performClick()
        compose.onNodeWithTag("first-run-all").performScrollTo().performClick()
        compose.onNodeWithTag("instruments-pane").assertIsDisplayed()
        compose.onNodeWithTag("instrument-clar_bb").performScrollTo().performClick()
        compose.onNodeWithTag("first-run-clar_bb").assertIsSelected()
    }
}
