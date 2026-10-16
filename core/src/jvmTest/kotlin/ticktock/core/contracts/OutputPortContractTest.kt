// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ticktock.core.contracts.fakes.FakeForegroundOwner
import ticktock.core.contracts.fakes.FakeOutputPort
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound
import ticktock.core.metro.MetroSetup

class OutputPortContractTest {
    private val setup = MetroSetup(
        bpm = 120,
        beatsPerBar = 4,
        beatUnit = 4,
        compoundPulse = false,
        accents = listOf(Accent.ACCENT, Accent.NORMAL, Accent.SOFT, Accent.MUTE),
        subdivision = 2,
        click = ClickSound.WOOD,
        gain = 0.8f,
        // GAP-01's example, so the gap bars cross the port with everything else.
        barsOn = 3,
        barsOff = 1,
    )

    /** One instance of every command variant. Keep in step with OutputCommand. */
    private val everyCommand: List<OutputCommand> = listOf(
        OutputCommand.Register(OutputTool.DRONE),
        OutputCommand.VoiceOn(slot = 0, midi = 62, voice = Voice.WARM, gain = 0.5f),
        OutputCommand.VoiceGain(slot = 0, gain = 0.25f),
        OutputCommand.SetA4(442.0),
        OutputCommand.Duck(0.3f, generation = 71),
        OutputCommand.MetroStart(setup),
        OutputCommand.MetroUpdate(setup.copy(bpm = 132)),
        OutputCommand.MetroStop,
        OutputCommand.Mute(MuteReason.RECORDING_ON_SPEAKER, active = true),
        OutputCommand.VoiceOff(slot = 0),
        OutputCommand.Unregister(OutputTool.DRONE),
    )

    @Test
    fun `every command round-trips through the port in order`() {
        val port = FakeOutputPort()
        everyCommand.forEach(port::send)
        assertEquals(everyCommand, port.sent)
    }

    @Test
    fun `commands survive a copy as plain data`() {
        // Every variant is a data class or object, so equality is structural: the
        // native struct on the other side of JNI can be rebuilt field by field.
        everyCommand.forEach { cmd ->
            val copy = when (cmd) {
                is OutputCommand.Register -> cmd.copy()
                is OutputCommand.Unregister -> cmd.copy()
                is OutputCommand.VoiceOn -> cmd.copy()
                is OutputCommand.VoiceOff -> cmd.copy()
                is OutputCommand.VoiceGain -> cmd.copy()
                is OutputCommand.SetA4 -> cmd.copy()
                is OutputCommand.Duck -> cmd.copy()
                is OutputCommand.MetroStart -> cmd.copy()
                is OutputCommand.MetroUpdate -> cmd.copy()
                OutputCommand.MetroStop -> OutputCommand.MetroStop
                is OutputCommand.Mute -> cmd.copy()
            }
            assertEquals(cmd, copy)
        }
    }

    @Test
    fun `every event round-trips through the flow in order`() = runTest {
        val port = FakeOutputPort()
        val info = FakeOutputPort.DEFAULT_INFO
        val everyEvent: List<OutputEvent> = listOf(
            OutputEvent.Opened(info),
            OutputEvent.Beat(index = 0, beatInBar = 0, frame = 0, nanos = 0),
            OutputEvent.Counters(lateBeatsDropped = 0, underruns = 0),
            OutputEvent.Parked(ParkReason.HEADPHONES_DISCONNECTED, setOf(OutputTool.METRONOME)),
            OutputEvent.Resumed(setOf(OutputTool.METRONOME), info.copy(sampleRate = 44_100)),
            OutputEvent.Parked(ParkReason.INCOMING_CALL, setOf(OutputTool.METRONOME)),
            OutputEvent.Failed("USB DAC", listOf(48_000, 44_100)),
            OutputEvent.Closed,
            OutputEvent.DuckSettled(71, 3_360, 5_123_000_000),
            OutputEvent.RoutingChanged(info.copy(deviceId = 9, latencyMillis = 81)),
        )
        everyEvent.forEach(port::emit)

        val collected = port.events.take(everyEvent.size).toList()
        assertEquals(everyEvent, collected)
        assertEquals(everyEvent, port.emitted)
    }

    @Test
    fun `the stream opens on the first registration and closes on the last`() = runTest {
        val port = FakeOutputPort()
        assertNull(port.info.value)

        port.send(OutputCommand.Register(OutputTool.METRONOME))
        port.send(OutputCommand.Register(OutputTool.DRONE))
        assertEquals(FakeOutputPort.DEFAULT_INFO, port.info.value)

        port.send(OutputCommand.Unregister(OutputTool.METRONOME))
        assertEquals(FakeOutputPort.DEFAULT_INFO, port.info.value)

        port.send(OutputCommand.Unregister(OutputTool.DRONE))
        assertNull(port.info.value)
        assertEquals(
            listOf(OutputEvent.Opened(FakeOutputPort.DEFAULT_INFO), OutputEvent.Closed),
            port.events.take(2).toList(),
        )
    }

    /**
     * Owner decision 4: another app's playback is never a reason to stop, so
     * the contract offers no focus-loss reason at all; an incoming call is the
     * one outside event that stops output, and it parks rather than mutes, so
     * nothing restarts until the player acts.
     */
    @Test
    fun `an incoming call parks every registered tool and no reason names audio focus`() = runTest {
        val port = FakeOutputPort()
        port.send(OutputCommand.Register(OutputTool.DRONE))
        port.send(OutputCommand.Register(OutputTool.METRONOME))

        port.incomingCall()

        val parked = port.emitted.filterIsInstance<OutputEvent.Parked>().single()
        assertEquals(ParkReason.INCOMING_CALL, parked.reason)
        assertEquals(setOf(OutputTool.DRONE, OutputTool.METRONOME), parked.tools)
        assertNull(port.info.value)
        assertEquals(emptyList<OutputEvent>(), port.emitted.filterIsInstance<OutputEvent.Resumed>())

        assertEquals(emptyList<String>(), ParkReason.entries.map { it.name }.filter { "FOCUS" in it })
        assertEquals(emptyList<String>(), MuteReason.entries.map { it.name }.filter { "FOCUS" in it })
    }

    /**
     * BGH-01: the registry's empty-to-one transition is the single place a
     * background-playback service may start, and one-to-empty the place it
     * stops. The owner's session brackets the stream: started before Opened,
     * stopped after Closed. 2.0 wires a no-op; v1.1 wires the service.
     */
    @Test
    fun `the foreground owner hears started on the first registration and stopped on the last`() = runTest {
        val owner = FakeForegroundOwner()
        val port = FakeOutputPort(owner)

        port.send(OutputCommand.Register(OutputTool.METRONOME))
        assertEquals(listOf("started"), owner.calls)
        port.send(OutputCommand.Register(OutputTool.DRONE))
        assertEquals(listOf("started"), owner.calls)

        port.send(OutputCommand.Unregister(OutputTool.METRONOME))
        assertEquals(listOf("started"), owner.calls)
        port.send(OutputCommand.Unregister(OutputTool.DRONE))
        assertEquals(listOf("started", "stopped"), owner.calls)

        // Unregistering a tool that never registered is not a transition.
        port.send(OutputCommand.Unregister(OutputTool.PITCH_PIPE))
        assertEquals(listOf("started", "stopped"), owner.calls)
    }

    @Test
    fun `the owner's session brackets the stream`() = runTest {
        val trace = mutableListOf<String>()
        lateinit var port: FakeOutputPort
        val owner = object : ForegroundOwner {
            override fun onToolsStarted() { trace += "started, stream open: ${port.info.value != null}" }
            override fun onToolsStopped() { trace += "stopped, stream open: ${port.info.value != null}" }
        }
        port = FakeOutputPort(owner)

        port.send(OutputCommand.Register(OutputTool.TEST_TONE))
        port.send(OutputCommand.Unregister(OutputTool.TEST_TONE))

        assertEquals(listOf("started, stream open: false", "stopped, stream open: false"), trace)
    }

    @Test
    fun `without an owner the port behaves as before`() = runTest {
        val port = FakeOutputPort()
        port.send(OutputCommand.Register(OutputTool.DRONE))
        port.send(OutputCommand.Unregister(OutputTool.DRONE))
        assertEquals(
            listOf(OutputEvent.Opened(FakeOutputPort.DEFAULT_INFO), OutputEvent.Closed),
            port.emitted,
        )
    }

    @Test
    fun `unplug parks the registered set and replug resumes exactly that set`() = runTest {
        val port = FakeOutputPort()
        port.send(OutputCommand.Register(OutputTool.METRONOME))
        port.send(OutputCommand.Register(OutputTool.PITCH_PIPE))

        port.unplug()
        assertNull(port.info.value)
        port.replug(sampleRate = 44_100)

        val parked = port.emitted.filterIsInstance<OutputEvent.Parked>().single()
        val resumed = port.emitted.filterIsInstance<OutputEvent.Resumed>().single()
        assertEquals(setOf(OutputTool.METRONOME, OutputTool.PITCH_PIPE), parked.tools)
        assertEquals(parked.tools, resumed.tools)
        assertEquals(44_100, port.info.value?.sampleRate)
    }
}
