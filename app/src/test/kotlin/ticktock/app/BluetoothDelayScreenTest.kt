// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration
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
import ticktock.app.output.TestOutputConnection
import ticktock.app.screens.ScreenRenderProbe
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.ParkReason

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class BluetoothDelayScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private val link = TestOutputConnection().apply { openInfo = TestOutputConnection.INFO.copy(wireless = true, deviceId = 7, deviceName = "Headset") }
    @After fun close() { ScreenRenderProbe.beforeRender = null; scenario?.close(); RuntimeEnvironment.setFontScale(1f) }
    private fun launch(scale: Float = 1f) {
        RuntimeEnvironment.setFontScale(scale)
        graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.tune.micAllowed = { false }
        graph.output.makeConnection = { link }
        graph.prefs.update { it.copy(firstRunComplete = true, visualOffsetMs = -17) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.runOnIdle { graph.output.startDrone(); graph.output.startMetro() }
    }
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test fun `ERR-01 actual Bluetooth delay dialog failure preserves calibration and navigation`() {
        launch()
        var attempts = 0
        var commandsAtFailure = emptyList<OutputCommand>()
        ScreenRenderProbe.beforeRender = { screen -> if (screen == "delay-dialog") {
            attempts++
            commandsAtFailure = link.sent.toList()
            throw IllegalStateException("Planted delay dialog failure")
        } }
        tap("bluetooth-measure")
        compose.onNodeWithTag("screen-failure-tune").assertIsDisplayed()
        compose.onNodeWithTag("delay-dialog").assertDoesNotExist()
        assertEquals(1, attempts)
        val measurement = graph.output.calibration.value
        assertNotNull(measurement)
        assertEquals(commandsAtFailure, link.sent)
        ScreenRenderProbe.beforeRender = null
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("delay-dialog").assertExists()
        assertSame(measurement, graph.output.calibration.value)
        assertEquals(commandsAtFailure, link.sent)
        tap("delay-cancel")
        assertTrue(graph.output.state.value.droneSounding && graph.output.state.value.metroSounding)
        assertNull(graph.prefs.prefs.value.bluetoothVisualOffsetMs)
    }

    @Test fun `BTC-01 a physical contact is measured before release and release adds no second tap`() {
        launch()
        tap("bluetooth-measure")
        val generation = link.sent.filterIsInstance<OutputCommand.MetroStart>().last().setup.generation
        compose.runOnIdle { link.events.tryEmit(OutputEvent.Beat(0, 0, 0, System.nanoTime(), generation = generation)) }
        compose.onNodeWithTag("delay-tap").performScrollTo().performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(1, graph.output.calibration.value?.taps) }
        compose.onNodeWithTag("delay-tap").performTouchInput { advanceEventTime(200); up() }
        compose.runOnIdle { assertEquals(1, graph.output.calibration.value?.taps) }
    }

    @Test fun `BTC-01 eight taps save the measured Bluetooth offset and restore both tools`() {
        launch()
        tap("bluetooth-measure")
        val generation = link.sent.filterIsInstance<OutputCommand.MetroStart>().last().setup.generation
        assertTrue(graph.output.current().isEmpty())
        repeat(8) { index ->
            // The shared clock follows real time; preserve the native 100 bpm cadence.
            if (index > 0) Thread.sleep(600)
            compose.runOnIdle { link.events.tryEmit(OutputEvent.Beat(index = index.toLong(), frame = index * 28_800L, nanos = System.nanoTime(), beatInBar = index % 4, bar = (index / 4).toLong(), generation = generation)) }
            compose.onNodeWithTag("delay-tap").performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            assertEquals("accepted tap $index", index + 1, graph.output.calibration.value?.taps)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        compose.waitUntil(5_000) { graph.output.calibration.value?.complete == true }
        val offset = graph.output.calibration.value!!.offsetMs!!
        assertTrue("offset $offset", offset in 0..500)
        compose.onNodeWithTag("delay-use").assertTextEquals("Use $offset ms")
        tap("delay-use")
        assertEquals(offset, graph.prefs.prefs.value.bluetoothVisualOffsetMs)
        assertEquals(-17, graph.prefs.prefs.value.visualOffsetMs)
        assertTrue(graph.output.state.value.droneSounding && graph.output.state.value.metroSounding)
    }

    @Test fun `Bluetooth notice dismissal is route scoped and double-text calibration cancels without saving`() {
        launch(2f)
        tap("bluetooth-dismiss")
        compose.onNodeWithTag("bluetooth-notice").assertDoesNotExist()
        compose.runOnIdle { link.info.value = link.openInfo.copy(deviceId = 8) }
        tap("bluetooth-measure")
        compose.onNodeWithTag("delay-tap").performScrollTo().assertIsDisplayed()
        tap("delay-cancel")
        assertNull(graph.prefs.prefs.value.bluetoothVisualOffsetMs)
        assertTrue(graph.output.state.value.droneSounding && graph.output.state.value.metroSounding)
        tap("bluetooth-measure")
        compose.runOnIdle { link.park(ParkReason.HEADPHONES_DISCONNECTED) }
        compose.onNodeWithTag("delay-dialog").assertDoesNotExist()
        assertFalse(graph.output.state.value.toolRunning)
        assertNull(graph.prefs.prefs.value.bluetoothVisualOffsetMs)
    }
}
