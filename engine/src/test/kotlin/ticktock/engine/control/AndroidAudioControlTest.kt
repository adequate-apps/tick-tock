// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason
import ticktock.core.contracts.RouteClass
import ticktock.core.contracts.Voice
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound
import ticktock.core.metro.MetroSetup
import ticktock.engine.output.NativeStreamBackend
import ticktock.engine.output.fakes.FakeNativeCalls
import ticktock.engine.output.fakes.FakeNativeCalls.Call
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The whole output stack as the app builds it: the control thread over the
 * native backend over the bridge, with the platform's audio manager for
 * devices and modes. Only the library is faked.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidAudioControlTest {
    private val context = RuntimeEnvironment.getApplication()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val native = FakeNativeCalls()
    private val events: MutableList<OutputEvent> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var stack: AndroidAudioControl
    private val control: AudioControlThread get() = stack.control

    private val setup = MetroSetup(
        bpm = 120, beatsPerBar = 4, beatUnit = 4, compoundPulse = false,
        accents = listOf(Accent.ACCENT, Accent.NORMAL, Accent.NORMAL, Accent.NORMAL),
        subdivision = 1, click = ClickSound.WOOD, gain = 0.8f,
    )

    @After
    fun tearDown() {
        if (::stack.isInitialized) {
            idle()
            control.shutdown()
        }
        scope.cancel()
    }

    private fun idle() = shadowOf(control.looper).idle()

    private fun send(vararg cmds: OutputCommand) {
        cmds.forEach(control::send)
        idle()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
            idle()
        }
    }

    private inline fun <reified T : OutputEvent> eventsOf(): List<T> = synchronized(events) { events.filterIsInstance<T>() }

    private fun startWithSpeaker(): AudioDeviceInfo {
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        shadowOf(audioManager).addOutputDevice(speaker, false)
        val platformSpeaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).single()
        native.deviceId = platformSpeaker.id.toLong()
        stack = AndroidAudioControl.create(context, native)
        scope.launch { control.events.collect { events += it } }
        idle()
        return platformSpeaker
    }

    @Test
    fun `OUT-01 OUT-12 OUT-14 the first tool opens the native stream and the info names the platform's device at the granted rate`() {
        val speaker = startWithSpeaker()
        native.sampleRate = 44_100
        assertNull(control.info.value)

        send(OutputCommand.Register(OutputTool.DRONE), OutputCommand.VoiceOn(slot = 0, midi = 69, voice = Voice.SINE, gain = 0.5f))

        val info = checkNotNull(control.info.value)
        assertEquals(44_100, info.sampleRate)
        assertEquals(RouteClass.SPEAKER, info.routeClass)
        assertEquals(speaker.productName.toString(), info.deviceName)
        assertEquals(23, info.latencyMillis)
        assertEquals(info, eventsOf<OutputEvent.Opened>().single().info)
        assertEquals(listOf(Call("voiceOn", listOf(0, 69, 0, 0.5f))), native.single.calls("voiceOn"))
        assertTrue(native.single.streamOpen)
        native.single.framesRendered = 4_410
        assertEquals(4_410L, stack.nativeDiagnostics()?.framesRendered)
    }

    @Test
    fun `MET-10 the metronome's setup reaches the native generator and its beats come back as events`() {
        startWithSpeaker()
        send(OutputCommand.Register(OutputTool.METRONOME), OutputCommand.MetroStart(setup))
        assertEquals(
            listOf(Call("metroStart", listOf(120, 4, 4, false, listOf(0, 1, 1, 1), 1, 0, 0.8f, 1, 0))),
            native.single.calls("metroStart"),
        )

        native.single.beat(index = 0, beatInBar = 0, frame = 0, nanos = 10_000)
        native.single.beat(index = 1, beatInBar = 1, frame = 24_000, nanos = 510_000_000)
        await("two beats") { eventsOf<OutputEvent.Beat>().size == 2 }
        assertEquals(OutputEvent.Beat(1, 1, 24_000, 510_000_000), eventsOf<OutputEvent.Beat>().last())
    }

    @Test
    fun `OUT-01 the last tool closes the native stream and the handle is destroyed`() {
        startWithSpeaker()
        send(OutputCommand.Register(OutputTool.DRONE), OutputCommand.Register(OutputTool.METRONOME))
        send(OutputCommand.Unregister(OutputTool.DRONE))
        assertTrue(native.single.streamOpen)
        send(OutputCommand.Unregister(OutputTool.METRONOME))
        assertNull(control.info.value)
        assertTrue(native.single.destroyed)
        assertNull(stack.nativeDiagnostics())
        assertEquals(1, eventsOf<OutputEvent.Closed>().size)
    }

    @Test
    fun `OUT-03 a native disconnect with the device gone parks every tool with the reason`() {
        val speaker = startWithSpeaker()
        send(OutputCommand.Register(OutputTool.DRONE), OutputCommand.Register(OutputTool.METRONOME))
        shadowOf(audioManager).removeOutputDevice(speaker, false)
        native.single.disconnect()
        await("parked") { eventsOf<OutputEvent.Parked>().isNotEmpty() }
        val parked = eventsOf<OutputEvent.Parked>().single()
        assertEquals(ParkReason.HEADPHONES_DISCONNECTED, parked.reason)
        assertEquals(setOf(OutputTool.DRONE, OutputTool.METRONOME), parked.tools)
        assertNull(control.info.value)
        assertTrue(native.single.destroyed)
    }

    @Test
    fun `OUT-13 a native disconnect with the device still present reopens on a fresh handle and replays the tools`() {
        startWithSpeaker()
        send(OutputCommand.Register(OutputTool.DRONE), OutputCommand.VoiceOn(slot = 0, midi = 69, voice = Voice.WARM, gain = 0.5f))
        native.sampleRate = 44_100
        native.single.disconnect()
        await("reopened") { native.handles.size == 2 && control.info.value?.sampleRate == 44_100 }
        assertEquals(listOf(Call("voiceOn", listOf(0, 69, 1, 0.5f))), native.handle(2).calls("voiceOn"))
        assertEquals(setOf(OutputTool.DRONE), eventsOf<OutputEvent.Resumed>().single().tools)
        assertTrue(native.handle(1).destroyed)
    }
}
