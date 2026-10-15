// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason
import ticktock.core.prefs.Prefs

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationSessionTest {
    private class Rig(val scope: TestScope) {
        var prefs = Prefs(visualOffsetMs = -20, bluetoothVisualOffsetMs = 90, metroBpm = 177, metroBarsOff = 3)
        val initial = prefs
        val link = TestOutputConnection().apply { openInfo = TestOutputConnection.INFO.copy(deviceId = 17, wireless = true) }
        val output: OutputSession = OutputSession(
            readPrefs = { prefs }, writePrefs = { prefs = it(prefs); changed() }, makeConnection = { link },
            scope = scope.backgroundScope, nanoTime = { scope.testScheduler.currentTime * 1_000_000L },
        )
        fun changed() = output.settingsChanged()
        fun start() {
            output.startDrone()
            output.startMetro()
            scope.testScheduler.runCurrent()
            output.beginCalibration()
            scope.testScheduler.runCurrent()
        }
        fun generation() = (link.sent.last { it is OutputCommand.MetroStart } as OutputCommand.MetroStart).setup.generation
        fun beat(index: Int, generation: Int = generation()) {
            link.events.tryEmit(OutputEvent.Beat(index = index.toLong(), frame = index * 28_800L, nanos = scope.testScheduler.currentTime * 1_000_000L,
                beatInBar = index % 4, bar = (index / 4).toLong(), silentBar = false, generation = generation, audible = true))
            scope.testScheduler.runCurrent()
        }
        fun complete(offset: Long = 200) {
            repeat(8) { index ->
                beat(index)
                scope.testScheduler.advanceTimeBy(offset)
                output.calibrationTap()
                scope.testScheduler.runCurrent()
                if (index < 7) scope.testScheduler.advanceTimeBy(600 - offset)
            }
        }
    }

    @Test
    fun `BTC-01 Use saves only Bluetooth offset after eight taps then restores both tools`() = runTest {
        val r = Rig(this)
        r.start()
        assertEquals(setOf(OutputTool.METRONOME), r.link.registered)
        assertTrue(r.output.current().isEmpty())
        assertTrue(r.output.state.value.toolRunning)
        val setup = (r.link.sent.last() as OutputCommand.MetroStart).setup
        assertEquals(100, setup.bpm)
        assertEquals(8, setup.beatLimit)
        assertEquals(0, setup.barsOff)
        assertEquals(r.initial, r.prefs)
        r.complete()
        assertEquals(200, r.output.calibration.value?.offsetMs)
        assertEquals(true, r.output.calibration.value?.complete)
        assertEquals(r.initial, r.prefs)
        r.output.useCalibration()
        testScheduler.runCurrent()
        assertNull(r.output.calibration.value)
        assertEquals(r.initial.copy(bluetoothVisualOffsetMs = 200), r.prefs)
        assertTrue(r.output.state.value.droneSounding && r.output.state.value.metroSounding)
        assertEquals(r.prefs.metroSetup(), (r.link.sent.last() as OutputCommand.MetroStart).setup.copy(generation = 0))
        r.link.info.value = TestOutputConnection.INFO
        testScheduler.runCurrent()
        assertEquals(-20, r.prefs.visualOffsetMs)
    }

    @Test
    fun `BTC-01 an incomplete run can retry and old beats cannot complete the next run`() = runTest {
        val r = Rig(this)
        r.start()
        val old = r.generation()
        r.beat(0)
        r.output.calibrationTap()
        testScheduler.advanceTimeBy(8_000)
        testScheduler.runCurrent()
        assertEquals(true, r.output.calibration.value?.complete)
        assertNull(r.output.calibration.value?.offsetMs)
        r.output.useCalibration()
        assertNotNull(r.output.calibration.value)
        r.output.retryCalibration()
        testScheduler.runCurrent()
        assertTrue(r.generation() != old)
        r.beat(7, old)
        assertEquals(0, r.output.calibration.value?.clicks)
        r.complete(100)
        assertEquals(100, r.output.calibration.value?.offsetMs)
        r.output.cancelCalibration()
        testScheduler.runCurrent()
        assertEquals(r.initial, r.prefs)
        assertTrue(r.output.state.value.droneSounding && r.output.state.value.metroSounding)
    }

    @Test
    fun `BTC-01 route loss calls and background cancel without restoring sounding intent`() = runTest {
        for (reason in listOf(ParkReason.HEADPHONES_DISCONNECTED, ParkReason.INCOMING_CALL, ParkReason.BACKGROUND)) {
            val r = Rig(this)
            r.start()
            if (reason == ParkReason.BACKGROUND) r.output.background() else r.link.park(reason)
            testScheduler.runCurrent()
            assertNull(r.output.calibration.value)
            assertFalse(r.output.state.value.droneWanted || r.output.state.value.metroWanted)
            assertTrue(r.link.registered.isEmpty())
            assertEquals(r.initial, r.prefs)
            r.link.replug()
            testScheduler.runCurrent()
            assertFalse(r.output.state.value.toolRunning)
        }
    }

    @Test
    fun `BTC-01 result waits for the final click tail and same-name route changes cancel it`() = runTest {
        val r = Rig(this)
        r.start()
        r.complete(0)
        assertEquals(false, r.output.calibration.value?.complete)
        r.output.useCalibration()
        assertNotNull(r.output.calibration.value)
        testScheduler.advanceTimeBy(29)
        testScheduler.runCurrent()
        assertEquals(false, r.output.calibration.value?.complete)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(0, r.output.calibration.value?.offsetMs)
        r.link.info.value = r.link.openInfo.copy(deviceId = 18)
        testScheduler.runCurrent()
        assertNull(r.output.calibration.value)
        assertFalse(r.output.state.value.toolRunning)
        assertEquals(r.initial, r.prefs)
    }
}
