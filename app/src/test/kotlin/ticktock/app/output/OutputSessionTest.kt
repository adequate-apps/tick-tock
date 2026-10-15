// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ticktock.core.contracts.*
import ticktock.core.prefs.Prefs
import ticktock.engine.control.ControlDiagnostics
import ticktock.engine.output.MuteSource
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class OutputSessionTest {
    private class Rig(scope: CoroutineScope, initial: Prefs = Prefs()) {
        var prefs = initial
        val link = TestOutputConnection()
        var made = 0
        val session: OutputSession = OutputSession(
            readPrefs = { prefs }, writePrefs = { prefs = it(prefs); sessionChanged() },
            makeConnection = { made++; link }, scope = scope,
        )
        fun sessionChanged() = session.settingsChanged()
        fun edit(change: (Prefs) -> Prefs) { prefs = change(prefs); sessionChanged() }
    }

    @Test
    fun `TON-04 five lower steps reach exact silence and ten raises reach full gain`() = runTest {
        val r = Rig(backgroundScope, Prefs(droneMidi = 69))
        r.session.startDrone()
        testScheduler.runCurrent()
        repeat(5) { r.session.droneVolume(-0.1f) }
        testScheduler.runCurrent()
        assertEquals(0f, r.prefs.droneGain, 0f)
        assertTrue(r.session.current().isEmpty())
        assertFalse(r.session.state.value.droneSounding)
        assertTrue(r.session.state.value.droneWanted)
        repeat(10) { r.session.droneVolume(0.1f) }
        testScheduler.runCurrent()
        assertEquals(1f, r.prefs.droneGain, 0f)
        assertEquals(1, r.link.sent.count { it is OutputCommand.VoiceOn })
    }

    @Test
    fun `TON-01 output is lazy and saved A4 precedes the first voice on a nonreplaying port`() = runTest {
        val r = Rig(backgroundScope, Prefs(a4Hz = 442.0, droneMidi = 69))
        assertEquals(0, r.made)
        r.session.startDrone()
        testScheduler.runCurrent()
        assertEquals(1, r.made)
        assertEquals(listOf(OutputCommand.SetA4(442.0), OutputCommand.Register(OutputTool.DRONE),
            OutputCommand.VoiceOn(0, 69, Voice.WARM, 0.5f)), r.link.sent)
        assertTrue(r.link.subscribedAtFirstRegister)
        assertTrue(r.session.state.value.droneSounding)
        assertEquals(69, r.session.soundingDroneMidi)
    }

    @Test
    fun `TON-04 gain updates preserve the voice and A4 retunes without a new strike`() = runTest {
        val r = Rig(backgroundScope, Prefs(droneMidi = 69))
        r.session.startDrone()
        testScheduler.runCurrent()
        r.link.sent.clear()
        r.edit { it.copy(droneGain = 0.7f) }
        r.edit { it.copy(a4Hz = 442.0) }
        r.edit { it.copy(capturePaused = true, earsFirst = true) }
        assertEquals(listOf(OutputCommand.VoiceGain(0, 0.7f), OutputCommand.SetA4(442.0)), r.link.sent)
        r.session.voice(Voice.ROOT_FIFTH)
        testScheduler.runCurrent()
        assertArrayEquals(intArrayOf(69, 76), r.session.current())
        assertEquals(69, r.session.soundingDroneMidi)
    }

    @Test
    fun `OUT-06 stopping a parked drone keeps it off when the metronome resumes`() = runTest {
        val r = Rig(backgroundScope)
        r.session.startDrone()
        r.session.startMetro()
        testScheduler.runCurrent()
        assertEquals(1, r.made)
        r.link.park(ParkReason.HEADPHONES_DISCONNECTED)
        testScheduler.runCurrent()
        assertFalse(r.session.state.value.toolRunning)
        assertNull(r.session.soundingDroneMidi)
        assertTrue(r.session.state.value.droneWanted)
        r.session.stopDrone()
        testScheduler.runCurrent()
        r.link.replug()
        testScheduler.runCurrent()
        assertFalse(r.session.state.value.droneWanted)
        assertTrue(r.session.state.value.metroSounding)
        assertEquals(setOf(OutputTool.METRONOME), r.link.registered)
    }

    @Test
    fun `OUT-11 background and calls stop both tools and foreground starts nothing`() = runTest {
        val r = Rig(backgroundScope)
        r.session.startDrone()
        r.session.startMetro()
        testScheduler.runCurrent()
        r.link.park(ParkReason.INCOMING_CALL)
        testScheduler.runCurrent()
        assertFalse(r.session.state.value.droneWanted)
        assertFalse(r.session.state.value.metroWanted)
        r.session.startDrone()
        r.session.startMetro()
        testScheduler.runCurrent()
        r.session.background()
        testScheduler.runCurrent()
        val sent = r.link.sent.toList()
        r.session.foreground()
        assertEquals(sent, r.link.sent)
        assertTrue(r.link.registered.isEmpty())
        assertFalse(r.session.state.value.toolRunning)
    }

    @Test
    fun `OUT-02 delayed old close and park facts cannot erase a newer open`() = runTest {
        val r = Rig(backgroundScope)
        r.session.startDrone()
        testScheduler.runCurrent()
        r.session.stopDrone()
        r.link.events.tryEmit(OutputEvent.Parked(ParkReason.INCOMING_CALL, setOf(OutputTool.DRONE)))
        r.session.startDrone()
        testScheduler.runCurrent()
        assertTrue(r.session.state.value.droneWanted)
        assertTrue(r.session.state.value.droneSounding)
        assertNull(r.session.state.value.parked)
    }

    @Test
    fun `OUT-02 queued command barriers retain new intent across an old park and ignore old acknowledgements`() = runTest {
        val link = TestOutputConnection()
        val batches = ArrayDeque<Pair<List<OutputCommand>, () -> Unit>>()
        val deferred = object : OutputConnection by link {
            override fun send(commands: List<OutputCommand>, applied: () -> Unit) {
                if (commands.isEmpty()) applied() else batches.addLast(commands to applied)
            }
        }
        val output = OutputSession({ Prefs() }, {}, { deferred }, scope = backgroundScope)
        output.startDrone()
        val first = batches.removeFirst()
        first.first.forEach(link::send)
        testScheduler.runCurrent()
        assertTrue(output.state.value.droneSounding)
        link.park(ParkReason.INCOMING_CALL)
        output.stopDrone()
        output.startDrone()
        output.startMetro()
        testScheduler.runCurrent()
        assertTrue("new intent survives the queued old park", output.state.value.droneWanted && output.state.value.metroWanted)
        first.second()
        testScheduler.runCurrent()
        assertTrue("old acknowledgement cannot settle the latest batch", output.state.value.droneWanted && output.state.value.metroWanted)
        while (batches.isNotEmpty()) {
            val next = batches.removeFirst()
            next.first.forEach(link::send)
            next.second()
            testScheduler.runCurrent()
        }
        assertTrue(output.state.value.droneSounding && output.state.value.metroSounding)
        link.park(ParkReason.INCOMING_CALL)
        testScheduler.runCurrent()
        assertFalse("a new call after the barrier clears both tools", output.state.value.droneWanted || output.state.value.metroWanted)
    }

    @Test
    fun `MET-06 stopped edits persist and running edits send a full generated update`() = runTest {
        val r = Rig(backgroundScope)
        r.session.bpm(900)
        assertEquals(300, r.prefs.metroBpm)
        assertEquals(0, r.made)
        r.session.startMetro()
        testScheduler.runCurrent()
        val start = (r.link.sent.last() as OutputCommand.MetroStart).setup
        r.session.preset(MetrePresets.all[4])
        val update = (r.link.sent.last() as OutputCommand.MetroUpdate).setup
        assertEquals(r.prefs.metroSetup(), update.copy(generation = 0))
        assertTrue(update.generation > start.generation)
        assertEquals(7, update.beatsPerBar)
        assertEquals(8, update.beatUnit)
        r.session.stopMetro()
        r.link.sent.clear()
        r.session.stepBpm(-1000)
        assertEquals(30, r.prefs.metroBpm)
        assertTrue(r.link.sent.isEmpty())
    }

    @Test
    fun `CAL-01 ringing updates sounding facts without closing the stream and clearing it resumes both`() = runTest {
        val r = Rig(backgroundScope)
        r.session.startDrone()
        r.session.startMetro()
        testScheduler.runCurrent()
        assertTrue(r.session.state.value.droneSounding && r.session.state.value.metroSounding)
        val info = r.link.info.value
        r.link.controlDiagnostics.value = ControlDiagnostics(muteSources = setOf(MuteSource.CALL_RINGING))
        testScheduler.runCurrent()
        assertEquals(info, r.link.info.value)
        assertFalse(r.session.state.value.droneSounding || r.session.state.value.metroSounding)
        assertTrue(r.session.state.value.droneWanted && r.session.state.value.metroWanted)
        r.link.controlDiagnostics.value = ControlDiagnostics()
        testScheduler.runCurrent()
        assertTrue(r.session.state.value.droneSounding && r.session.state.value.metroSounding)
    }

    @Test
    fun `BTC-01 notice dismissal resets for an equal-name different device`() = runTest {
        val r = Rig(backgroundScope)
        r.link.openInfo = TestOutputConnection.INFO.copy(deviceId = 1, deviceName = "Headset", wireless = true)
        r.session.startMetro()
        testScheduler.runCurrent()
        assertTrue(r.session.state.value.bluetoothNotice)
        r.session.dismissBluetooth()
        assertFalse(r.session.state.value.bluetoothNotice)
        r.link.info.value = r.link.openInfo.copy(deviceId = 2)
        testScheduler.runCurrent()
        assertTrue(r.session.state.value.bluetoothNotice)
        r.link.info.value = TestOutputConnection.INFO
        testScheduler.runCurrent()
        assertFalse(r.session.state.value.bluetoothNotice)
    }
}

/** No replay and actual registry teardown, matching the engine's event order. */
internal class TestOutputConnection : OutputConnection, OutputPort, SoundingNotes {
    override val port: OutputPort get() = this
    override val soundingNotes: SoundingNotes get() = this
    override val events = MutableSharedFlow<OutputEvent>(extraBufferCapacity = 128)
    override val info = MutableStateFlow<OutputInfo?>(null)
    override val controlDiagnostics = MutableStateFlow(ControlDiagnostics())
    var acknowledgeDucks = true
    val duckGain = AtomicReference(1f)
    val sent = mutableListOf<OutputCommand>()
    val registered = mutableSetOf<OutputTool>()
    var openInfo = INFO
    var subscribedAtFirstRegister = false
    private var voice: OutputCommand.VoiceOn? = null

    override fun send(commands: List<OutputCommand>, applied: () -> Unit) {
        commands.forEach(::send)
        applied()
    }
    override fun send(cmd: OutputCommand) {
        sent += cmd
        when (cmd) {
            is OutputCommand.Register -> {
                subscribedAtFirstRegister = events.subscriptionCount.value > 0
                registered += cmd.tool
                if (info.value == null) {
                    info.value = openInfo
                    events.tryEmit(OutputEvent.Opened(openInfo))
                }
            }
            is OutputCommand.Unregister -> {
                registered -= cmd.tool
                if (registered.isEmpty()) {
                    info.value = null
                    events.tryEmit(OutputEvent.Closed)
                }
            }
            is OutputCommand.Duck -> {
                duckGain.set(cmd.gain)
                if (acknowledgeDucks && cmd.generation != 0) events.tryEmit(OutputEvent.DuckSettled(cmd.generation, 0, 0))
            }
            is OutputCommand.VoiceOn -> voice = cmd
            is OutputCommand.VoiceGain -> voice = voice?.copy(gain = cmd.gain)
            is OutputCommand.VoiceOff -> voice = null
            else -> Unit
        }
    }
    fun park(reason: ParkReason) {
        val tools = registered.toSet()
        info.value = null
        if (reason != ParkReason.HEADPHONES_DISCONNECTED) { registered.clear(); voice = null }
        events.tryEmit(OutputEvent.Parked(reason, tools))
    }
    fun replug() {
        duckGain.set(1f)
        info.value = openInfo
        events.tryEmit(OutputEvent.Resumed(registered.toSet(), openInfo))
    }
    override fun current(): IntArray = voice?.takeIf { it.gain > 0f && info.value != null && controlDiagnostics.value.muteSources.isEmpty() }?.let {
        if (it.voice == Voice.ROOT_FIFTH) intArrayOf(it.midi, it.midi + 7) else intArrayOf(it.midi)
    } ?: intArrayOf()
    override fun background() = park(ParkReason.BACKGROUND)
    override fun foreground() = Unit
    override fun close() = Unit
    companion object { val INFO = OutputInfo(48_000, 240, RouteClass.SPEAKER, "Speaker", 20) }
}
