// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import ticktock.core.contracts.ForegroundOwner
import ticktock.core.contracts.MuteReason
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason
import ticktock.core.contracts.RouteClass
import ticktock.core.contracts.Voice
import ticktock.core.contracts.fakes.FakeForegroundOwner
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound
import ticktock.core.metro.MetroSetup
import ticktock.engine.control.fakes.FakeAudioModes
import ticktock.engine.control.fakes.FakeOutputDevices
import ticktock.engine.output.MuteSource
import ticktock.engine.output.StreamOpenFailure
import ticktock.engine.output.StreamRequest
import ticktock.engine.output.fakes.FakeStreamBackend
import ticktock.engine.output.fakes.FakeStreamBackend.Companion.BLE_HEADSET
import ticktock.engine.output.fakes.FakeStreamBackend.Companion.SPEAKER
import ticktock.engine.output.fakes.FakeStreamBackend.Companion.USB_DAC
import ticktock.engine.output.fakes.FakeStreamBackend.Companion.WIRED_HEADSET
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The control thread against the fake backend, devices and modes. Every
 * scenario drives the real `audio-control` looper: `idle` is a barrier that
 * returns once everything posted so far has run, and `idleFor` moves the
 * clock so the timing rules (the 2 Hz poll, the xrun poll) are exact.
 */
@RunWith(RobolectricTestRunner::class)
class AudioControlThreadTest {
    private val backend = FakeStreamBackend()
    private val devices = FakeOutputDevices(listOf(SPEAKER))
    private val modes = FakeAudioModes()
    private val owner = FakeForegroundOwner()
    private val events: MutableList<OutputEvent> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var control: AudioControlThread

    private val setup = MetroSetup(
        bpm = 120, beatsPerBar = 4, beatUnit = 4, compoundPulse = false,
        accents = listOf(Accent.ACCENT, Accent.NORMAL, Accent.NORMAL, Accent.NORMAL),
        subdivision = 1, click = ClickSound.WOOD, gain = 0.8f, barsOn = 1, barsOff = 0,
    )
    private val drone = OutputCommand.VoiceOn(slot = 0, midi = 62, voice = Voice.WARM, gain = 0.7f)

    private fun start(
        apiLevel: Int = 36,
        foregroundOwner: ForegroundOwner = owner,
        policy: BackgroundPolicy = BackgroundPolicy.StopEverything,
    ) {
        control = AudioControlThread(backend, devices, modes, foregroundOwner, policy, apiLevel)
        scope.launch { control.events.collect { events += it } }
        control.start()
        idle()
    }

    @After
    fun tearDown() {
        if (::control.isInitialized) {
            idle()
            control.shutdown()
        }
        scope.cancel()
    }

    private fun idle() = shadowOf(control.looper).idle()
    private fun idleFor(millis: Long) = shadowOf(control.looper).idleFor(Duration.ofMillis(millis))
    private fun send(vararg cmds: OutputCommand) {
        cmds.forEach(control::send)
        idle()
    }

    private inline fun <reified T : OutputEvent> eventsOf(): List<T> = synchronized(events) { events.filterIsInstance<T>() }
    private fun register(vararg tools: OutputTool) = send(*tools.map { OutputCommand.Register(it) }.toTypedArray())

    /** Headphones in: the speaker is still there, the stream is on the headset. */
    private fun startOnHeadset() {
        devices.add(WIRED_HEADSET)
        backend.nextRouted = WIRED_HEADSET
        start()
    }

    @Test
    fun `POL-24 the sounding snapshot follows gain through zero without closing the stream`() {
        start()
        register(OutputTool.DRONE)
        send(drone.copy(voice = Voice.ROOT_FIFTH, gain = 0f))
        assertArrayEquals(intArrayOf(), control.soundingNotes.current())
        send(OutputCommand.VoiceGain(drone.slot, 0.7f))
        assertArrayEquals(intArrayOf(62, 69), control.soundingNotes.current())
        send(OutputCommand.VoiceGain(drone.slot, 0f))
        assertArrayEquals(intArrayOf(), control.soundingNotes.current())
        assertEquals(1, backend.opened.size)
        assertNotNull(control.info.value)
        assertFalse(backend.current!!.closed)
    }

    // --- OUT-01, BGH-01, SHM-01, OUT-14 ---------------------------------------------

    @Test
    fun `OUT-01 the stream opens on the first registered tool and closes on the last`() {
        start()
        assertNull(control.info.value)

        register(OutputTool.METRONOME)
        assertEquals(1, backend.opened.size)
        assertNotNull(control.info.value)
        register(OutputTool.DRONE)
        assertEquals(1, backend.opened.size)

        send(OutputCommand.Unregister(OutputTool.METRONOME))
        assertNotNull(backend.current)
        send(OutputCommand.Unregister(OutputTool.DRONE))
        assertNull(backend.current)
        assertNull(control.info.value)
        val opened = eventsOf<OutputEvent.Opened>().single()
        assertEquals(SPEAKER.name, opened.info.deviceName)
        assertEquals(listOf(opened, OutputEvent.Closed), synchronized(events) { events.toList() })
    }

    @Test
    fun `BGH-01 the owner hears started before the stream opens and stopped after it closes`() {
        val trace = mutableListOf<String>()
        val bracketing = object : ForegroundOwner {
            override fun onToolsStarted() { trace += "started, open streams: ${backend.opened.count { !it.closed }}" }
            override fun onToolsStopped() { trace += "stopped, open streams: ${backend.opened.count { !it.closed }}" }
        }
        start(foregroundOwner = bracketing)

        register(OutputTool.TEST_TONE, OutputTool.DRONE)
        send(OutputCommand.Unregister(OutputTool.TEST_TONE), OutputCommand.Unregister(OutputTool.DRONE))
        send(OutputCommand.Unregister(OutputTool.PITCH_PIPE))

        assertEquals(listOf("started, open streams: 0", "stopped, open streams: 0"), trace)
    }

    @Test
    fun `SHM-01 and OUT-14 the request is shared, low latency and unspecified, and scheduling uses the granted rate`() {
        backend.nextSampleRate = 44_100
        backend.nextFramesPerBurst = 192
        start()
        register(OutputTool.DRONE)

        val request = backend.requests.single()
        assertEquals(StreamRequest.Sharing.SHARED, request.sharing)
        assertEquals(StreamRequest.Performance.LOW_LATENCY, request.performance)
        assertEquals(StreamRequest.UNSPECIFIED, request.sampleRate)
        assertEquals(StreamRequest.UNSPECIFIED, request.framesPerCallback)
        assertEquals(2, request.bufferBursts)

        val info = checkNotNull(control.info.value)
        assertEquals(44_100, info.sampleRate)
        assertEquals(192, info.framesPerBlock)
        assertEquals(SPEAKER.name, info.deviceName)
        assertEquals(RouteClass.SPEAKER, info.routeClass)
        assertEquals(20, info.latencyMillis)
    }

    // --- OUT-15, OUT-17 ------------------------------------------------------------------

    @Test
    fun `OUT-15 send is a dispatch and nothing more, and the open runs on the control thread`() {
        start()
        val gate = CountDownLatch(1)
        backend.openGate = gate

        // The driver is stuck inside open; the caller is back before it moves.
        control.send(OutputCommand.Register(OutputTool.DRONE))
        control.send(drone)
        assertTrue(backend.openStarted.await(5, TimeUnit.SECONDS))
        assertEquals(0, backend.requests.size)
        assertNull(control.info.value)

        gate.countDown()
        idle()
        assertEquals(1, backend.requests.size)
        assertEquals(listOf<OutputCommand>(drone), backend.current?.sent)
        assertEquals(listOf(AudioControlThread.THREAD_NAME), backend.openThreads)
    }

    @Test
    fun `OUT-15 a stop that times out never leaves output dead for the rest of the session`() {
        backend.nextCloseTimesOut = true
        start()
        register(OutputTool.DRONE)
        send(OutputCommand.Unregister(OutputTool.DRONE))
        assertTrue(backend.opened.single().closed)
        assertTrue(control.diagnostics.value.log.any { "close timed out" in it })

        backend.nextCloseTimesOut = false
        register(OutputTool.METRONOME)
        assertEquals(2, backend.opened.size)
        assertNotNull(backend.current)
        assertEquals(2, eventsOf<OutputEvent.Opened>().size)
    }

    @Test
    fun `OUT-17 an open failure names the device and the refused rates, and the tool parks`() {
        backend.refusal = StreamOpenFailure("USB DAC", listOf(48_000, 44_100))
        start()
        register(OutputTool.DRONE)

        assertEquals(listOf(OutputEvent.Failed("USB DAC", listOf(48_000, 44_100))), eventsOf<OutputEvent.Failed>())
        assertEquals(listOf(OutputEvent.Parked(ParkReason.OUTPUT_FAILED, setOf(OutputTool.DRONE))), eventsOf<OutputEvent.Parked>())
        assertEquals(listOf("started", "stopped"), owner.calls)
        assertNull(control.info.value)

        // The next start tries again from scratch.
        backend.refusal = null
        register(OutputTool.DRONE)
        assertNotNull(control.info.value)
        assertEquals(listOf("started", "stopped", "started"), owner.calls)
    }

    // --- OUT-03, OUT-05, OUT-06, OUT-07, OUT-13 ------------------------------------------

    @Test
    fun `OUT-03 unplug parks every registered tool, closes the stream and opens nothing on the speaker`() {
        startOnHeadset()
        register(OutputTool.DRONE, OutputTool.METRONOME)
        send(drone, OutputCommand.MetroStart(setup))
        assertArrayEquals(intArrayOf(62), control.soundingNotes.current())

        devices.becomingNoisy()
        idle()

        assertEquals(listOf(OutputEvent.Parked(ParkReason.HEADPHONES_DISCONNECTED, setOf(OutputTool.DRONE, OutputTool.METRONOME))), eventsOf<OutputEvent.Parked>())
        assertNull(backend.current)
        assertEquals(1, backend.opened.size)
        assertNull(control.info.value)
        assertArrayEquals(intArrayOf(), control.soundingNotes.current())
        // The tools still want output: the owner's session stays open for the replug.
        assertEquals(listOf("started"), owner.calls)

        // The platform's own disconnect for the dead stream, and the device
        // vanishing after it, change nothing more.
        backend.opened.single().disconnect()
        devices.remove(WIRED_HEADSET)
        idle()
        assertEquals(1, eventsOf<OutputEvent.Parked>().size)
        assertEquals(1, backend.opened.size)
    }

    @Test
    fun `OUT-03 a device vanishing without a noisy broadcast parks the same way`() {
        startOnHeadset()
        register(OutputTool.DRONE)
        devices.remove(WIRED_HEADSET)
        idle()
        assertEquals(listOf(OutputEvent.Parked(ParkReason.HEADPHONES_DISCONNECTED, setOf(OutputTool.DRONE))), eventsOf<OutputEvent.Parked>())
        assertNull(backend.current)
    }

    @Test
    fun `OUT-05 OUT-13 and MET-09 replug restarts exactly the parked set at the new rate, continuing the engine the unplug kept`() {
        startOnHeadset()
        register(OutputTool.DRONE, OutputTool.METRONOME)
        send(OutputCommand.SetA4(442.0), drone, OutputCommand.MetroStart(setup))
        devices.becomingNoisy()
        idle()
        // The unplug parks the stream and keeps its engine: the drone's phase
        // and the metronome's count wait for the route with the tools.
        assertEquals(true, backend.opened.single().engineKept)
        assertNull(backend.parked?.takeIf { it !== backend.opened.single() })

        backend.nextSampleRate = 44_100
        backend.nextRouted = USB_DAC
        devices.add(USB_DAC)
        idle()

        val resumed = eventsOf<OutputEvent.Resumed>().single()
        assertEquals(setOf(OutputTool.DRONE, OutputTool.METRONOME), resumed.tools)
        assertEquals(44_100, resumed.info.sampleRate)
        assertEquals(RouteClass.HEADPHONES, resumed.info.routeClass)
        assertEquals(USB_DAC.name, resumed.info.deviceName)
        assertEquals(resumed.info, control.info.value)
        val continued = checkNotNull(backend.current)
        assertTrue("the new stream continues the kept engine", continued.continuesEngine)
        // The metronome is an edit of the running generator, never a restart:
        // a MetroStart here would click at once and restart the bar (MET-09).
        assertEquals(
            listOf(OutputCommand.SetA4(442.0), drone, OutputCommand.MetroUpdate(setup)),
            continued.sent,
        )
        assertArrayEquals(intArrayOf(62), control.soundingNotes.current())
        assertEquals(listOf("started"), owner.calls)
        assertEquals(1, eventsOf<OutputEvent.Opened>().size)
        assertEquals(0, backend.discards)
    }

    @Test
    fun `BTC-01 an explicit finite restart queued while parked reaches the resumed stream as a start`() {
        startOnHeadset()
        register(OutputTool.METRONOME)
        val previous = setup.copy(bpm = 100, beatLimit = 8, generation = 20)
        send(OutputCommand.MetroStart(previous))
        devices.becomingNoisy()
        idle()
        assertEquals(true, backend.opened.single().engineKept)
        val requested = previous.copy(generation = 21)
        val edited = requested.copy(gain = 0.6f)
        send(OutputCommand.MetroStart(requested), OutputCommand.MetroUpdate(edited))
        backend.nextRouted = USB_DAC
        devices.add(USB_DAC)
        idle()
        val resumed = checkNotNull(backend.current)
        assertTrue(resumed.continuesEngine)
        assertEquals(listOf(OutputCommand.MetroStart(edited)), resumed.sent)
    }

    @Test
    fun `OUT-06 what the player switches off while unplugged is switched off in the continued engine too`() {
        startOnHeadset()
        register(OutputTool.DRONE, OutputTool.METRONOME)
        send(drone, OutputCommand.MetroStart(setup))
        devices.becomingNoisy()
        idle()

        // No stream to send to, and the engine still holds both.
        send(OutputCommand.VoiceOff(0), OutputCommand.MetroStop)
        devices.add(BLE_HEADSET)
        idle()

        assertEquals(
            listOf(OutputCommand.VoiceOff(0), OutputCommand.MetroStop),
            checkNotNull(backend.current).sent,
        )
        assertArrayEquals(intArrayOf(), control.soundingNotes.current())
    }

    @Test
    fun `TON-01 A4 outlives the tools, so a drone started after the registry emptied still sounds against it`() {
        start()
        send(OutputCommand.SetA4(442.0))
        register(OutputTool.DRONE)
        send(drone, OutputCommand.Unregister(OutputTool.DRONE))
        assertEquals(listOf(OutputCommand.SetA4(442.0), drone), backend.opened.single().sent)

        register(OutputTool.DRONE)
        assertEquals(listOf<OutputCommand>(OutputCommand.SetA4(442.0)), checkNotNull(backend.current).sent)
        assertFalse(checkNotNull(backend.current).continuesEngine)
    }

    @Test
    fun `OUT-05 a speaker appearing is not a replug`() {
        startOnHeadset()
        register(OutputTool.DRONE)
        devices.becomingNoisy()
        idle()
        devices.add(SPEAKER.copy(id = 7))
        idle()
        assertEquals(emptyList<OutputEvent.Resumed>(), eventsOf<OutputEvent.Resumed>())
        assertNull(backend.current)
    }

    @Test
    fun `OUT-06 a tool switched off while unplugged stays off when the route returns`() {
        startOnHeadset()
        register(OutputTool.DRONE, OutputTool.METRONOME)
        devices.becomingNoisy()
        idle()

        send(OutputCommand.Unregister(OutputTool.DRONE))
        devices.add(BLE_HEADSET)
        idle()

        assertEquals(setOf(OutputTool.METRONOME), eventsOf<OutputEvent.Resumed>().single().tools)
        assertEquals(listOf("started"), owner.calls)
    }

    @Test
    fun `OUT-06 when every parked tool is switched off the registry empties and a replug restarts nothing`() {
        startOnHeadset()
        register(OutputTool.DRONE, OutputTool.METRONOME)
        send(drone)
        devices.becomingNoisy()
        idle()

        send(OutputCommand.Unregister(OutputTool.DRONE), OutputCommand.Unregister(OutputTool.METRONOME))
        assertEquals(listOf("started", "stopped"), owner.calls)
        assertEquals(1, eventsOf<OutputEvent.Closed>().size)

        devices.add(BLE_HEADSET)
        idle()
        assertEquals(emptyList<OutputEvent.Resumed>(), eventsOf<OutputEvent.Resumed>())
        assertEquals(1, backend.opened.size)

        // Whatever sounded before the unplug is forgotten with the registry,
        // and the engine the unplug kept goes with it.
        assertEquals(1, backend.discards)
        register(OutputTool.DRONE)
        val fresh = checkNotNull(backend.current)
        assertEquals(emptyList<OutputCommand>(), fresh.sent)
        assertFalse(fresh.continuesEngine)
    }

    @Test
    fun `OUT-05 a tool the player starts while unplugged brings the parked set back on the route in hand`() {
        startOnHeadset()
        register(OutputTool.DRONE)
        send(drone)
        devices.becomingNoisy()
        idle()

        backend.nextRouted = SPEAKER
        register(OutputTool.METRONOME)

        assertEquals(setOf(OutputTool.DRONE), eventsOf<OutputEvent.Resumed>().single().tools)
        assertEquals(RouteClass.SPEAKER, control.info.value?.routeClass)
        assertEquals(listOf<OutputCommand>(drone), checkNotNull(backend.current).sent)
        assertEquals(listOf("started"), owner.calls)
    }

    @Test
    fun `OUT-07 a headset plugged in after start-up becomes the route when the speaker stream ends`() {
        start()
        register(OutputTool.DRONE)
        send(drone)
        assertEquals(RouteClass.SPEAKER, control.info.value?.routeClass)

        // Before API 37 the route change ends the stream; the speaker is still
        // present, so this is a move and never a park.
        devices.add(WIRED_HEADSET)
        backend.nextRouted = WIRED_HEADSET
        backend.opened.single().disconnect()
        idle()

        assertEquals(emptyList<OutputEvent.Parked>(), eventsOf<OutputEvent.Parked>())
        assertEquals(setOf(OutputTool.DRONE), eventsOf<OutputEvent.Resumed>().single().tools)
        assertEquals(RouteClass.HEADPHONES, control.info.value?.routeClass)
        assertEquals(WIRED_HEADSET.name, control.info.value?.deviceName)
        assertEquals(listOf<OutputCommand>(drone), checkNotNull(backend.current).sent)
        assertEquals(2, backend.opened.size)
        // A move keeps the engine: the drone continues without a gap (OUT-13).
        assertEquals(true, backend.opened.first().engineKept)
        assertTrue(checkNotNull(backend.current).continuesEngine)
    }

    @Test
    fun `OUT-02 a stream that keeps ending with its device present is output failing, after three reopens in place`() {
        start()
        register(OutputTool.DRONE)
        send(drone)
        repeat(AudioControlThread.MAX_REOPENS_IN_PLACE) {
            checkNotNull(backend.current).disconnect()
            idle()
        }
        assertEquals(AudioControlThread.MAX_REOPENS_IN_PLACE + 1, backend.opened.size)
        assertEquals(emptyList<OutputEvent.Parked>(), eventsOf<OutputEvent.Parked>())

        checkNotNull(backend.current).disconnect()
        idle()
        assertEquals(listOf(OutputEvent.Failed(SPEAKER.name, emptyList())), eventsOf<OutputEvent.Failed>())
        assertEquals(listOf(OutputEvent.Parked(ParkReason.OUTPUT_FAILED, setOf(OutputTool.DRONE))), eventsOf<OutputEvent.Parked>())
        assertNull(backend.current)
        assertEquals(listOf("started", "stopped"), owner.calls)
    }

    @Test
    fun `OUT-02 a stream that lives a second is a working one, and the reopen budget starts over`() {
        start()
        register(OutputTool.DRONE)
        repeat(AudioControlThread.MAX_REOPENS_IN_PLACE) {
            checkNotNull(backend.current).disconnect()
            idle()
        }
        idleFor(AudioControlThread.XRUN_POLL_MILLIS)
        repeat(AudioControlThread.MAX_REOPENS_IN_PLACE) {
            checkNotNull(backend.current).disconnect()
            idle()
        }
        assertEquals(emptyList<OutputEvent.Failed>(), eventsOf<OutputEvent.Failed>())
        assertNotNull(backend.current)
    }

    @Test
    fun `OUT-12 the route comes from the routed device and an API 37 route change re-classifies without a disconnect`() {
        backend.nextRouted = BLE_HEADSET
        start()
        register(OutputTool.METRONOME)
        assertEquals(RouteClass.HEADPHONES, control.info.value?.routeClass)
        assertEquals(true, control.route.value?.wireless)
        assertEquals(BLE_HEADSET.id, control.info.value?.deviceId)
        assertEquals(true, control.info.value?.wireless)

        val stream = checkNotNull(backend.current)
        stream.latency = 8
        stream.routeChanged(SPEAKER)
        idle()

        assertEquals(RouteClass.SPEAKER, control.info.value?.routeClass)
        assertEquals(false, control.route.value?.wireless)
        assertEquals(SPEAKER.id, control.info.value?.deviceId)
        assertEquals(false, control.info.value?.wireless)
        assertEquals(8, control.info.value?.latencyMillis)
        assertEquals(SPEAKER.name, control.info.value?.deviceName)
        assertEquals(1, backend.opened.size)
        assertEquals(emptyList<OutputEvent.Parked>(), eventsOf<OutputEvent.Parked>())
        assertTrue(control.diagnostics.value.log.any { "route is now" in it })
    }

    @Test
    fun `BTC-01 equal output names retain different route identities`() {
        backend.nextRouted = BLE_HEADSET
        start()
        register(OutputTool.METRONOME)
        val first = checkNotNull(control.info.value)
        val replacement = BLE_HEADSET.copy(id = BLE_HEADSET.id + 1)
        checkNotNull(backend.current).routeChanged(replacement)
        idle()
        val next = checkNotNull(control.info.value)
        assertEquals(first.deviceName, next.deviceName)
        assertEquals(replacement.id, next.deviceId)
        assertTrue(first != next)
        assertTrue(next.wireless)
    }

    // --- OUT-10, CAL-01, CAL-02 ---------------------------------------------------------------

    @Test
    fun `OUT-10 reasons overlap in any order and output is audible only when all have cleared`() {
        start()
        register(OutputTool.DRONE)
        send(drone)
        val stream = checkNotNull(backend.current)
        assertEquals(false, stream.muted)

        send(OutputCommand.Mute(MuteReason.RECORDING_ON_SPEAKER, active = true))
        assertEquals(true, stream.muted)
        assertArrayEquals(intArrayOf(), control.soundingNotes.current())

        modes.setMode(AudioManager.MODE_RINGTONE)
        idle()
        send(OutputCommand.Mute(MuteReason.RECORDING_ON_SPEAKER, active = false))
        assertEquals(true, stream.muted)
        assertEquals(setOf(MuteSource.CALL_RINGING), control.diagnostics.value.muteSources)

        modes.setMode(AudioManager.MODE_NORMAL)
        idle()
        assertEquals(false, stream.muted)
        assertArrayEquals(intArrayOf(62), control.soundingNotes.current())
        assertEquals(emptyList<OutputEvent.Parked>(), eventsOf<OutputEvent.Parked>())
    }

    @Test
    fun `OUT-10 a mute set before the stream opens is in force when it opens`() {
        start()
        send(OutputCommand.Mute(MuteReason.BACKGROUND, active = true))
        register(OutputTool.DRONE)
        assertEquals(true, backend.current?.muted)
    }

    @Test
    fun `CAL-01 on API 28 ringing mutes within 500 ms and a declined call costs nothing`() {
        start(apiLevel = 28)
        register(OutputTool.DRONE)
        send(drone)
        val stream = checkNotNull(backend.current)

        modes.setMode(AudioManager.MODE_RINGTONE)
        idleFor(CallWatcher.POLL_MILLIS)
        assertEquals(true, stream.muted)
        assertEquals(CallState.RINGING, control.diagnostics.value.callState)

        modes.setMode(AudioManager.MODE_NORMAL)
        idleFor(CallWatcher.POLL_MILLIS)
        assertEquals(false, stream.muted)
        assertFalse(stream.closed)
        assertEquals(emptyList<OutputEvent.Parked>(), eventsOf<OutputEvent.Parked>())
        assertEquals(listOf("started"), owner.calls)
    }

    @Test
    fun `CAL-01 on API 31 and later the listener mutes at once`() {
        start(apiLevel = 31)
        register(OutputTool.PITCH_PIPE)
        modes.setMode(AudioManager.MODE_RINGTONE)
        idle()
        assertEquals(true, backend.current?.muted)
    }

    @Test
    fun `CAL-02 an answered call parks every tool with the call reason and nothing resumes when it ends`() {
        startOnHeadset()
        register(OutputTool.DRONE, OutputTool.METRONOME)
        send(drone, OutputCommand.MetroStart(setup))

        modes.setMode(AudioManager.MODE_RINGTONE)
        modes.setMode(AudioManager.MODE_IN_CALL)
        idle()

        assertEquals(listOf(OutputEvent.Parked(ParkReason.INCOMING_CALL, setOf(OutputTool.DRONE, OutputTool.METRONOME))), eventsOf<OutputEvent.Parked>())
        assertNull(backend.current)
        assertNull(control.info.value)
        assertEquals(listOf("started", "stopped"), owner.calls)

        modes.setMode(AudioManager.MODE_NORMAL)
        devices.add(BLE_HEADSET)
        idle()
        assertEquals(emptyList<OutputEvent.Resumed>(), eventsOf<OutputEvent.Resumed>())
        assertEquals(1, backend.opened.size)

        // The player starts again from nothing: no stale voice comes back.
        assertEquals(false, backend.opened.single().engineKept)
        register(OutputTool.DRONE)
        assertEquals(false, backend.current?.muted)
        assertEquals(emptyList<OutputCommand>(), checkNotNull(backend.current).sent)
        assertFalse(checkNotNull(backend.current).continuesEngine)
    }

    @Test
    fun `CAL-02 a call while the tools wait for a route drops them and the engine the unplug kept`() {
        startOnHeadset()
        register(OutputTool.DRONE)
        send(drone)
        devices.becomingNoisy()
        idle()
        assertEquals(true, backend.opened.single().engineKept)

        modes.setMode(AudioManager.MODE_IN_CALL)
        idle()
        assertEquals(ParkReason.INCOMING_CALL, eventsOf<OutputEvent.Parked>().last().reason)
        assertEquals(1, backend.discards)
        assertEquals(listOf("started", "stopped"), owner.calls)
    }

    // --- OUT-11 -----------------------------------------------------------------------------

    @Test
    fun `OUT-11 the background stops everything and the foreground restarts nothing`() {
        start()
        register(OutputTool.DRONE, OutputTool.METRONOME)
        send(drone)

        control.appBackgrounded()
        idle()
        assertEquals(listOf(OutputEvent.Parked(ParkReason.BACKGROUND, setOf(OutputTool.DRONE, OutputTool.METRONOME))), eventsOf<OutputEvent.Parked>())
        assertNull(backend.current)
        assertEquals(listOf("started", "stopped"), owner.calls)

        control.appForegrounded()
        idle()
        assertNull(backend.current)
        assertEquals(emptyList<OutputEvent.Resumed>(), eventsOf<OutputEvent.Resumed>())

        register(OutputTool.DRONE)
        assertEquals(emptyList<OutputCommand>(), checkNotNull(backend.current).sent)
    }

    @Test
    fun `OUT-11 the same policy applies to every tool, and one that keeps some parks only the rest, which stop sounding`() {
        start(policy = BackgroundPolicy { it.filterTo(mutableSetOf()) { tool -> tool == OutputTool.METRONOME } })
        register(OutputTool.DRONE, OutputTool.METRONOME)
        send(drone, OutputCommand.MetroStart(setup))
        control.appBackgrounded()
        idle()
        assertEquals(listOf(OutputEvent.Parked(ParkReason.BACKGROUND, setOf(OutputTool.DRONE))), eventsOf<OutputEvent.Parked>())
        val stream = checkNotNull(backend.current)
        // A parked drone makes no sound (OUT-02): every voice slot is off and
        // the metronome, kept, runs on.
        assertEquals(
            (0 until AudioControlThread.VOICE_SLOTS).map { OutputCommand.VoiceOff(it) },
            stream.sent.drop(2),
        )
        assertArrayEquals(intArrayOf(), control.soundingNotes.current())
        assertEquals(listOf("started"), owner.calls)
    }

    @Test
    fun `OUT-11 a policy that keeps the drone stops the parked metronome`() {
        start(policy = BackgroundPolicy { it.filterTo(mutableSetOf()) { tool -> tool == OutputTool.DRONE } })
        register(OutputTool.DRONE, OutputTool.METRONOME)
        send(drone, OutputCommand.MetroStart(setup))
        control.appBackgrounded()
        idle()
        assertEquals(listOf(OutputEvent.Parked(ParkReason.BACKGROUND, setOf(OutputTool.METRONOME))), eventsOf<OutputEvent.Parked>())
        assertEquals(listOf(drone, OutputCommand.MetroStart(setup), OutputCommand.MetroStop), checkNotNull(backend.current).sent)
        assertArrayEquals(intArrayOf(62), control.soundingNotes.current())
    }

    @Test
    fun `a voice command for a slot the engine lacks is refused aloud rather than ignored natively`() {
        start()
        register(OutputTool.DRONE)
        send(OutputCommand.VoiceOn(slot = AudioControlThread.VOICE_SLOTS, midi = 69, voice = Voice.SINE, gain = 0.5f), OutputCommand.VoiceOff(slot = -1))
        assertEquals(emptyList<OutputCommand>(), checkNotNull(backend.current).sent)
        assertArrayEquals(intArrayOf(), control.soundingNotes.current())
        assertEquals(2, control.diagnostics.value.log.count { "refused" in it })
    }

    @Test
    fun `OUT-11 the background with nothing registered is nothing`() {
        start()
        control.appBackgrounded()
        idle()
        assertEquals(emptyList<OutputEvent>(), synchronized(events) { events.toList() })
    }

    // --- SHM-02, events from the render side ---------------------------------------------------

    @Test
    fun `SHM-02 underruns grow the buffer one burst at a time up to four and each step is recorded`() {
        start()
        register(OutputTool.METRONOME)
        val stream = checkNotNull(backend.current)
        assertEquals(2, control.diagnostics.value.bufferBursts)

        idleFor(AudioControlThread.XRUN_POLL_MILLIS)
        assertEquals(2, stream.bursts)

        stream.xruns = 1
        idleFor(AudioControlThread.XRUN_POLL_MILLIS)
        assertEquals(3, stream.bursts)
        stream.xruns = 2
        idleFor(AudioControlThread.XRUN_POLL_MILLIS)
        assertEquals(4, stream.bursts)
        stream.xruns = 9
        idleFor(AudioControlThread.XRUN_POLL_MILLIS)
        assertEquals(4, stream.bursts)

        val diagnostics = control.diagnostics.value
        assertEquals(4, diagnostics.bufferBursts)
        assertEquals(9, diagnostics.xruns)
        assertEquals(2, diagnostics.log.count { "bursts" in it })
    }

    @Test
    fun `SHM-02 two steps the render callback took inside one poll are two Diagnostics lines, and a refused step is none`() {
        start()
        register(OutputTool.METRONOME)
        val stream = checkNotNull(backend.current)
        stream.nextGrant = 4
        stream.xruns = 2
        idleFor(AudioControlThread.XRUN_POLL_MILLIS)
        assertEquals(listOf("buffer 2 to 3 bursts", "buffer 3 to 4 bursts"), control.diagnostics.value.log.map { it.substringAfter(": ") })
        assertEquals(4, control.diagnostics.value.bufferBursts)

        val refusing = FakeStreamBackend()
        val second = AudioControlThread(refusing, devices, modes, owner, BackgroundPolicy.StopEverything, 36)
        second.start()
        second.send(OutputCommand.Register(OutputTool.METRONOME))
        shadowOf(second.looper).idle()
        val clamped = checkNotNull(refusing.current)
        clamped.nextGrant = 2
        clamped.xruns = 1
        shadowOf(second.looper).idleFor(Duration.ofMillis(AudioControlThread.XRUN_POLL_MILLIS))
        assertEquals(listOf("buffer stays at 2 bursts"), second.diagnostics.value.log.map { it.substringAfter(": ") })
        assertEquals(2, second.diagnostics.value.bufferBursts)
        second.shutdown()
    }

    @Test
    fun `beats and counters from the stream reach the flow, and a dropped stream's do not`() {
        start()
        register(OutputTool.METRONOME)
        val first = checkNotNull(backend.current)
        first.event(OutputEvent.Beat(index = 0, beatInBar = 0, frame = 0, nanos = 0))
        idle()
        assertEquals(1, eventsOf<OutputEvent.Beat>().size)

        send(OutputCommand.Unregister(OutputTool.METRONOME))
        first.event(OutputEvent.Counters(lateBeatsDropped = 1, underruns = 0))
        idle()
        assertEquals(emptyList<OutputEvent.Counters>(), eventsOf<OutputEvent.Counters>())
    }
}
