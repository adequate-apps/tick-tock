// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.ParkReason
import ticktock.core.contracts.Voice
import ticktock.core.prefs.Prefs
import ticktock.engine.control.ControlDiagnostics
import ticktock.engine.output.MuteSource

@OptIn(ExperimentalCoroutinesApi::class)
class DuckPresentationTest {
    private class Rig(val scope: TestScope) {
        var prefs = Prefs(droneMidi = 69)
        val link = TestOutputConnection().apply { acknowledgeDucks = false }
        val output = OutputSession({ prefs }, {}, { link }, scope = scope.backgroundScope,
            nanoTime = { scope.testScheduler.currentTime * 1_000_000L })
        init { output.startDrone(); scope.testScheduler.runCurrent() }
        fun latest() = link.sent.filterIsInstance<OutputCommand.Duck>().last()
        fun acknowledge(command: OutputCommand.Duck = latest(), nanos: Long = 0) {
            link.events.tryEmit(OutputEvent.DuckSettled(command.generation, 240, nanos))
            scope.testScheduler.runCurrent()
        }
        fun edit(change: (Prefs) -> Prefs) { prefs = change(prefs); output.settingsChanged(); scope.testScheduler.runCurrent() }
    }

    @Test
    fun `POL-28 command application and early event delivery cannot release capture before presentation`() = runTest {
        val r = Rig(this)
        var settled = 0
        r.output.duck(0.3f) { settled++ }
        testScheduler.runCurrent()
        assertEquals(0, settled)
        val command = r.latest()
        r.acknowledge(command.copy(generation = command.generation + 1))
        assertEquals(0, settled)
        r.acknowledge(command, 200_100_000L)
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()
        assertEquals(0, settled)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(1, settled)
        r.acknowledge(command)
        assertEquals("one fence release", 1, settled)
    }

    @Test
    fun `POL-28 repeated target requests replace the previous fence and its scheduled acknowledgement`() = runTest {
        val r = Rig(this)
        var old = 0
        var fresh = 0
        r.output.duck(1f) { old++ }
        val first = r.latest()
        r.acknowledge(first, 100_000_000L)
        r.output.duck(1f) { fresh++ }
        val second = r.latest()
        assertNotEquals(first.generation, second.generation)
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()
        r.acknowledge(first)
        assertEquals(0, old)
        assertEquals(0, fresh)
        r.acknowledge(second)
        assertEquals(1, fresh)
    }

    @Test
    fun `SHM-03 same-route facts cancel scheduled and late duck proof before a fresh probe`() = runTest {
        val r = Rig(this)
        var old = 0
        var fresh = 0
        var invalidations = 0
        r.output.bleedContextChanged = { invalidations++ }
        r.output.duck(0.3f) { old++ }
        val first = r.latest()
        r.acknowledge(first, 100_000_000L)
        // Identical info must still convey changed physical routing/presentation context.
        val info = checkNotNull(r.link.info.value)
        r.link.events.tryEmit(OutputEvent.RoutingChanged(info))
        r.link.events.tryEmit(OutputEvent.DuckSettled(first.generation, 240, 0))
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()
        assertEquals(0, old)
        assertTrue(invalidations > 0)
        assertTrue(r.output.state.value.droneSounding)
        r.output.duck(0.3f) { fresh++ }
        r.acknowledge(first)
        assertEquals(0, fresh)
        r.acknowledge()
        assertEquals(1, fresh)
    }

    @Test
    fun `POL-28 replug cancels both delivered and scheduled acknowledgements from the old stream`() = runTest {
        val r = Rig(this)
        var old = 0
        var fresh = 0
        r.output.duck(0.3f) { old++ }
        val first = r.latest()
        r.acknowledge(first, 100_000_000L)
        r.link.park(ParkReason.HEADPHONES_DISCONNECTED)
        testScheduler.runCurrent()
        r.link.replug()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()
        r.acknowledge(first)
        assertEquals(0, old)
        r.output.duck(1f) { fresh++ }
        r.acknowledge()
        assertEquals(1, fresh)
    }

    @Test
    fun `POL-28 pitch voice gain A4 and mute changes cancel proof while unrelated preferences retain it`() = runTest {
        val r = Rig(this)
        var invalidations = 0
        r.output.bleedContextChanged = { invalidations++ }
        val changes: List<() -> Unit> = listOf(
            { r.edit { it.copy(droneMidi = 72) } },
            { r.edit { it.copy(droneVoice = Voice.SINE) } },
            { r.edit { it.copy(droneGain = 0.8f) } },
            { r.edit { it.copy(a4Hz = 442.0) } },
            { r.link.controlDiagnostics.value = ControlDiagnostics(muteSources = setOf(MuteSource.CALL_RINGING)); testScheduler.runCurrent() },
            { r.link.controlDiagnostics.value = ControlDiagnostics(); testScheduler.runCurrent() },
        )
        for (change in changes) {
            var settled = false
            r.output.duck(0.3f) { settled = true }
            val command = r.latest()
            val before = invalidations
            change()
            assertTrue(invalidations > before)
            r.acknowledge(command)
            assertFalse(settled)
        }
        var settled = false
        r.output.duck(1f) { settled = true }
        val before = invalidations
        r.edit { it.copy(earsFirst = true, capturePaused = true) }
        assertEquals(before, invalidations)
        r.acknowledge()
        assertTrue(settled)
    }

    @Test
    fun `POL-28 stop and close revoke a scheduled acknowledgement`() = runTest {
        val r = Rig(this)
        var settled = false
        r.output.duck(0.3f) { settled = true }
        val first = r.latest()
        r.acknowledge(first, 100_000_000L)
        r.output.stopDrone()
        testScheduler.runCurrent()
        r.output.close()
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()
        assertFalse(settled)
        assertEquals(1f, r.link.duckGain.get())
    }
}
