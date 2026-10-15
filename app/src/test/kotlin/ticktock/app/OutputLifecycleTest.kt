// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.app.output.TestOutputConnection

@RunWith(RobolectricTestRunner::class)
class OutputLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `OUT-11 recreation retains tools while leaving the foreground stops them`() {
        val graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.tune.micAllowed = { false }
        graph.output.makeConnection = { TestOutputConnection() }
        graph.prefs.update { it.copy(firstRunComplete = true) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { graph.output.startDrone(); graph.output.startMetro() }
            compose.waitForIdle()
            assertTrue(graph.output.state.value.droneWanted && graph.output.state.value.metroWanted)
            scenario.recreate()
            compose.waitForIdle()
            assertTrue("configuration recreation keeps foreground intent", graph.output.state.value.droneWanted && graph.output.state.value.metroWanted)
            scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitForIdle()
            assertFalse(graph.output.state.value.droneWanted || graph.output.state.value.metroWanted)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertFalse(graph.output.state.value.droneWanted || graph.output.state.value.metroWanted)
        }
    }
}
