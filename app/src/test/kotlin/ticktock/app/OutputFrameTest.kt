// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.app.output.TestOutputConnection
import ticktock.core.contracts.RouteClass

@RunWith(RobolectricTestRunner::class)
class OutputFrameTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    @After fun close() { scenario?.close() }
    @Test fun `Tool running keeps an output-only session awake and marks its tabs and actual route`() {
        val graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        val link = TestOutputConnection().apply { openInfo = TestOutputConnection.INFO.copy(routeClass = RouteClass.HEADPHONES) }
        graph.output.makeConnection = { link }
        graph.tune.micAllowed = { false }
        graph.prefs.update { it.copy(firstRunComplete = true, keepAwake = true) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.runOnIdle { graph.output.startDrone(); graph.output.startMetro() }
        compose.onAllNodes(hasTestTag("running") and hasAnyAncestor(hasTestTag("tabs-1")), useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("running") and hasAnyAncestor(hasTestTag("tabs-2")), useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasText("Headphones") and hasAnyAncestor(hasTestTag("statusbar")), useUnmergedTree = true).assertExists()
        fun anyAwake(view: View): Boolean = view.keepScreenOn || (view is ViewGroup && (0 until view.childCount).any { anyAwake(view.getChildAt(it)) })
        scenario!!.onActivity { assertTrue(anyAwake(it.window.decorView)) }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("keep-awake-0").performScrollTo().assertTextEquals("Tool running")
        compose.onNodeWithTag("keep-awake-1").performClick()
        compose.waitForIdle()
        scenario!!.onActivity { assertFalse(anyAwake(it.window.decorView)) }
        compose.runOnIdle { graph.prefs.update { it.copy(keepAwake = true) }; graph.output.stopDrone(); graph.output.stopMetro() }
        compose.waitForIdle()
        scenario!!.onActivity { assertFalse(anyAwake(it.window.decorView)) }
    }
}
