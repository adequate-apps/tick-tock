// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.content.Intent
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.app.debug.InputSeam
import ticktock.app.debug.ToneInput
import ticktock.core.detect.HopAnalyser

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class DiagnosticsScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    @After fun close() { scenario?.close() }

    @Test fun `Diagnostics shows actual dropped hops from the oversized debug input seam`() {
        val context = RuntimeEnvironment.getApplication()
        val graph = TickTockApp.graph(context)
        graph.prefs.update { it.copy(firstRunComplete = true) }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(InputSeam.EXTRA_TONE_HZ, 440f)
            .putExtra(InputSeam.EXTRA_BURST_HOPS, HopAnalyser.DEFAULT_RING_HOPS * 4)
        scenario = ActivityScenario.launch(intent)
        compose.waitUntil(5_000) { compose.waitForIdle(); graph.tune.diagnostics.value.counters.droppedHops > 0 }
        val drops = graph.tune.diagnostics.value.counters.droppedHops
        assertTrue(drops >= HopAnalyser.DEFAULT_RING_HOPS * 3)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-diagnostics").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("diag-dropped").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("diag-dropped").performScrollTo()
        compose.onNode(hasText(drops.toString()) and hasAnyAncestor(hasTestTag("diag-dropped"))).assertIsDisplayed()
        compose.onNodeWithTag("diag-input").performScrollTo()
        compose.onNodeWithText(ToneInput.DEVICE_NAME).assertIsDisplayed()
        compose.onNodeWithTag("diag-input-rate").performScrollTo()
        compose.onNodeWithText("48000 Hz").assertIsDisplayed()
    }
    @Test fun `CAP-03 Diagnostics includes readings skipped while capture is paused`() {
        val graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.history.setContext(ticktock.data.history.RunContext("eb_alto", -9, captured = false))
        repeat(7) { graph.history.accept(ticktock.core.detect.Reading(it.toLong(), 69, 440.0, 0.0, 440.0, 0), false) }
        val fact = ticktock.app.screens.diagnosticFacts(graph).singleOrNull { it.tag == "diag-paused" }
        assertNotNull("Diagnostics exposes the paused-reading counter", fact)
        assertEquals("7", fact!!.value)
    }

}
