// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.core.detect.Reading
import ticktock.core.prefs.Theme
import kotlin.math.pow

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class TableScreenshotTest(private val scale: Float, private val theme: Theme, private val width: Int) {
    @get:Rule val compose = createEmptyComposeRule()
    private val originalZone = java.util.TimeZone.getDefault()
    private var scenario: ActivityScenario<MainActivity>? = null
    @After fun close() { scenario?.close(); RuntimeEnvironment.setFontScale(1f); java.util.TimeZone.setDefault(originalZone) }
    private fun golden(name: String) = compose.onNodeWithTag("frame").captureRoboImage("src/test/screenshots/table-$name-${width}x800-fs$scale-${theme.name.lowercase()}.png")

    @Test fun `table distributions note detail and save remain reachable across text sizes and themes`() {
        RuntimeEnvironment.setQualifiers("w${width}dp-h800dp-xhdpi")
        RuntimeEnvironment.setFontScale(scale)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        val graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.tune.micAllowed = { false }
        // Keep the capture date stable when the golden is checked on another day.
        graph.history.javaClass.getDeclaredField("wallClock").apply { isAccessible = true }
            .set(graph.history, { 1_792_065_600_000L })
        graph.prefs.update { it.copy(firstRunComplete = true, instrumentKey = "eb_alto", theme = theme, lastEquipment = "Mouthpiece 6 · Reed 3") }
        runBlocking {
            var hop = 0L
            repeat(30) { n ->
                val cents = -15.0 + n % 5
                graph.history.accept(Reading(hop++, 49, 440.0 * 2.0.pow((49 - 69 + cents / 100) / 12), cents, 440.0, 0), n % 10 == 0)
            }
            graph.history.flushAndWait()
            graph.history.saveSession(graph.history.currentSessionId()!!, "Earlier session", "Reed 2")
            graph.history.newSession()
            for ((midi, center) in listOf(49 to -3, 51 to 2, 52 to -9, 53 to 11, 62 to 9, 66 to 4)) {
                repeat(if (midi == 66) 3 else 48) { n ->
                    val cents = center + (n % 9 - 4).toDouble()
                    graph.history.accept(Reading(hop++, midi, 440.0 * 2.0.pow((midi - 69 + cents / 100) / 12), cents, 440.0, 0), n % 12 == 0)
                }
            }
            graph.history.flushAndWait()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("tabs-3").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("table-note-49").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("table-scope").performScrollTo()
        golden("session")
        compose.onNodeWithTag("table-note-66").performScrollTo().assertIsDisplayed()
        golden("rows")
        compose.onNodeWithTag("table-note-49").performScrollTo().performClick()
        compose.onNodeWithTag("table-back").performScrollTo().assertIsDisplayed()
        golden("detail")
        compose.onNodeWithTag("table-stats-history").performScrollTo().assertIsDisplayed()
        golden("detail-statistics")
        compose.onNodeWithTag("table-clear-note").performScrollTo().performClick()
        compose.onNodeWithTag("clear-cancel").performScrollTo().assertIsDisplayed()
        golden("clear-note")
        compose.onNodeWithTag("clear-cancel").performClick()
        compose.onNodeWithTag("table-back").performScrollTo().performClick()
        compose.onNodeWithTag("table-save").performScrollTo().performClick()
        compose.onNodeWithTag("session-name").performTextInput("Tuesday long tones")
        compose.onNodeWithTag("session-name").performScrollTo()
        golden("save")
        compose.onNodeWithTag("session-save").performScrollTo().assertIsDisplayed()
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "fs{0}-{1}-w{2}")
        fun cases(): List<Array<Any>> = listOf(360, 600).flatMap { width ->
            listOf(1f, 1.3f, 1.5f, 2f).map { arrayOf<Any>(it, Theme.GRIDLINE, width) } +
                Theme.entries.filter { it != Theme.GRIDLINE }.map { arrayOf<Any>(1f, it, width) }
        }
    }
}
