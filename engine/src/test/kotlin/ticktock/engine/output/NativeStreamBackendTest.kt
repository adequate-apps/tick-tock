// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import android.media.AudioDeviceInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.MuteReason
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason
import ticktock.core.contracts.Voice
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound
import ticktock.core.metro.MetroSetup
import ticktock.engine.output.fakes.FakeNativeCalls
import ticktock.engine.output.fakes.FakeNativeCalls.Call
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The StreamBackend the control thread talks to on a device, over a fake of
 * the JNI bridge: one native handle per engine, one holder registration that
 * keeps the native registry non-empty, a park that keeps the handle for the
 * next open to resume, an event thread that turns the bridge's numbers into
 * the listener's calls, and a close that never hangs the control thread.
 */
class NativeStreamBackendTest {
    private val native = FakeNativeCalls()
    private val speaker = RoutedDevice(id = 7, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, name = "Built-in speaker")
    private val present = mutableListOf(speaker)
    private val backend = NativeStreamBackend(native) { present.toList() }

    private val disconnected = Collections.synchronizedList(mutableListOf<OpenStream>())
    private val routed = Collections.synchronizedList(mutableListOf<OpenStream>())
    private val events = Collections.synchronizedList(mutableListOf<Pair<OpenStream, OutputEvent>>())
    private val listener = object : StreamListener {
        override fun onDisconnected(stream: OpenStream) { disconnected += stream }
        override fun onRoutingChanged(stream: OpenStream) { routed += stream }
        override fun onEvent(stream: OpenStream, event: OutputEvent) { events += stream to event }
    }
    private var stream: OpenStream? = null

    private val setup = MetroSetup(
        bpm = 132, beatsPerBar = 3, beatUnit = 8, compoundPulse = true,
        accents = listOf(Accent.ACCENT, Accent.SOFT, Accent.MUTE),
        subdivision = 2, click = ClickSound.BEEP, gain = 0.6f, barsOn = 2, barsOff = 1, beatLimit = 8, generation = 37,
    )

    @After
    fun tearDown() {
        native.releaseDrains()
        stream?.close()
    }

    // Event threads already alive when this test opened its stream. Another test's stream (the
    // OUT-15 case leaks one on purpose) may still be winding down, so a test waits for its own
    // thread to end and never for every thread of that name in the JVM.
    private var threadsBeforeOpen: Set<Thread> = emptySet()

    private fun eventThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == NativeStreamBackend.EVENT_THREAD_NAME && it.isAlive }.toSet()

    private fun ownEventThreadGone(): Boolean = (eventThreads() - threadsBeforeOpen).isEmpty()

    private fun open(): OpenStream {
        threadsBeforeOpen = eventThreads()
        return backend.open(StreamRequest(), listener).getOrThrow().also { stream = it }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun `open registers one holder and reports the granted rate, burst, routed device and latency`() {
        native.sampleRate = 44_100
        native.framesPerBurst = 192
        native.latencyMillis = 31
        val opened = open()

        val handle = native.single
        assertEquals(listOf(Call("register", listOf(NativeStreamBackend.HOLDER.ordinal))), handle.calls("register"))
        assertTrue(handle.streamOpen)
        assertEquals(44_100, opened.sampleRate)
        assertEquals(192, opened.framesPerBurst)
        assertEquals(speaker, opened.routedDevice())
        assertEquals(31, opened.latencyMillis())
        // The control thread emits Opened itself, from the facts above.
        assertEquals(emptyList<Pair<OpenStream, OutputEvent>>(), events.toList())
    }

    @Test
    fun `OUT-03 the routed device is the one present at open, so a removal still matches after the platform has dropped it`() {
        val opened = open()
        present.clear()
        assertEquals(speaker, opened.routedDevice())
    }

    @Test
    fun `OUT-17 a refused open fails with the device name and the refused rates and destroys its handle`() {
        native.refuseOpens = true
        val failure = backend.open(StreamRequest(), listener).exceptionOrNull() as StreamOpenFailure
        assertEquals("Built-in speaker", failure.deviceName)
        assertEquals(listOf(48_000, 44_100), failure.refusedRates)
        assertTrue(native.single.destroyed)
    }

    @Test
    fun `a refused open on a device the platform cannot name falls back to the id`() {
        native.refuseOpens = true
        present.clear()
        val failure = backend.open(StreamRequest(), listener).exceptionOrNull() as StreamOpenFailure
        assertEquals("output device 7", failure.deviceName)
    }

    @Test
    fun `SHM-01 a request the bridge cannot honour is refused before the library is touched`() {
        val exclusive = backend.open(StreamRequest(sharing = StreamRequest.Sharing.EXCLUSIVE), listener)
        assertTrue(exclusive.exceptionOrNull() is IllegalArgumentException)
        val pinnedRate = backend.open(StreamRequest(sampleRate = 48_000), listener)
        assertTrue(pinnedRate.exceptionOrNull() is IllegalArgumentException)
        assertTrue(native.handles.isEmpty())
    }

    @Test
    fun `every DSP command reaches its native method with ordinals, and the registry commands never do`() {
        val opened = open()
        opened.send(OutputCommand.SetA4(442.0))
        opened.send(OutputCommand.VoiceOn(slot = 1, midi = 62, voice = Voice.ROOT_FIFTH, gain = 0.7f))
        opened.send(OutputCommand.VoiceGain(slot = 1, gain = 0.4f))
        opened.send(OutputCommand.Duck(0.3f, generation = 71))
        opened.send(OutputCommand.MetroStart(setup))
        opened.send(OutputCommand.MetroUpdate(setup.copy(bpm = 140)))
        opened.send(OutputCommand.MetroStop)
        opened.send(OutputCommand.VoiceOff(slot = 1))
        opened.send(OutputCommand.Register(OutputTool.DRONE))
        opened.send(OutputCommand.Unregister(OutputTool.DRONE))
        opened.send(OutputCommand.Mute(MuteReason.BACKGROUND, true))

        val metro = listOf(132, 3, 8, true, listOf(0, 2, 3), 2, 2, 0.6f, 2, 1, 8, 37)
        assertEquals(
            listOf(
                Call("register", listOf(NativeStreamBackend.HOLDER.ordinal)),
                Call("setA4", listOf(442.0)),
                Call("voiceOn", listOf(1, 62, 2, 0.7f)),
                Call("voiceGain", listOf(1, 0.4f)),
                Call("duck", listOf(0.3f, 71)),
                Call("metroStart", metro),
                Call("metroUpdate", listOf(140) + metro.drop(1)),
                Call("metroStop", emptyList()),
                Call("voiceOff", listOf(1)),
            ),
            native.single.calls.toList(),
        )
    }

    @Test
    fun `OUT-10 the one mute boolean drives one native mute slot`() {
        val opened = open()
        opened.setMuted(true)
        opened.setMuted(false)
        assertEquals(
            listOf(Call("mute", listOf(NativeStreamBackend.MUTE_SLOT, true)), Call("mute", listOf(NativeStreamBackend.MUTE_SLOT, false))),
            native.single.calls("mute"),
        )
    }

    @Test
    fun `beats, duck acknowledgements and counters reach the listener for this stream, on the event thread`() {
        val opened = open()
        val handle = native.single
        handle.beat(index = 0, beatInBar = 0, frame = 0, nanos = 1_000)
        handle.beat(index = 1, beatInBar = 1, frame = 21_818, nanos = 455_000_000,
            bar = 0x1ffffffffL, silentBar = true, generation = 37, audible = false)
        handle.counters(late = 1, underruns = 2)
        handle.duckSettled(71, 3_360, 5_123_000_000)

        await("four events") { events.size == 4 }
        assertEquals(
            listOf(
                OutputEvent.Beat(0, 0, 0, 1_000),
                OutputEvent.Beat(1, 1, 21_818, 455_000_000,
                    bar = 0x1ffffffffL, silentBar = true, generation = 37, audible = false),
                OutputEvent.Counters(1, 2),
                OutputEvent.DuckSettled(71, 3_360, 5_123_000_000),
            ),
            events.map { it.second },
        )
        assertTrue(events.all { it.first === opened })
        assertEquals(setOf(NativeStreamBackend.EVENT_THREAD_NAME), native.drainThreads.toSet())
    }

    @Test
    fun `OUT-03 a park from the native side is a disconnect to the listener`() {
        val opened = open()
        native.single.disconnect()
        await("disconnect") { disconnected.isNotEmpty() }
        assertEquals(listOf(opened), disconnected.toList())
        assertEquals(emptyList<Pair<OpenStream, OutputEvent>>(), events.toList())
    }

    @Test
    fun `close unregisters the holder, ends the event thread and destroys the handle`() {
        val opened = open()
        val handle = native.single
        assertTrue(opened.close())
        stream = null
        assertEquals(listOf(Call("unregister", listOf(NativeStreamBackend.HOLDER.ordinal))), handle.calls("unregister"))
        assertFalse(handle.streamOpen)
        assertTrue(handle.destroyed)
        // Closed came from the native side and was swallowed: the control thread emits its own.
        assertEquals(emptyList<Pair<OpenStream, OutputEvent>>(), events.toList())
        await("event thread gone") { ownEventThreadGone() }
    }

    @Test
    fun `OUT-15 a close the library does not finish in time returns false and keeps the handle alive`() {
        native.hangDrains = true
        val opened = open()
        val handle = native.single
        await("event thread inside the library") { NativeStreamBackend.EVENT_THREAD_NAME in native.drainThreads }
        val started = System.nanoTime()
        assertFalse(opened.close())
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("close waited $waited ms", waited in NativeStreamBackend.CLOSE_TIMEOUT_MILLIS..NativeStreamBackend.CLOSE_TIMEOUT_MILLIS + 2_000)
        assertFalse("a handle a thread may still be inside is never destroyed", handle.destroyed)
        stream = null
    }

    @Test
    fun `SHM-02 the underrun count and the buffer size are the native counters`() {
        val opened = open()
        val handle = native.single
        assertEquals(0, opened.xrunCount())
        handle.underruns = 3
        handle.bufferBursts = 3
        assertEquals(3, opened.xrunCount())
        // The render callback grows the buffer itself (decision 13); the control thread reads what it holds.
        assertEquals(3, opened.setBufferBursts(4))
    }

    @Test
    fun `MET-10 the native counters of the open stream are readable, and nothing is reported while closed`() {
        assertNull(backend.diagnostics())
        val opened = open()
        val handle = native.single
        handle.framesRendered = 96_000
        handle.underruns = 2
        handle.bufferBursts = 3
        handle.lateBeatsDropped = 1
        assertEquals(
            NativeDiagnostics(
                lateBeatsDropped = 1, underruns = 2, bufferBursts = 3, growthSteps = 0, droppedCommands = 0,
                droppedBeats = 0, framesRendered = 96_000, sampleRate = 48_000, streamOpen = true, parked = false,
            ),
            backend.diagnostics(),
        )
        assertTrue(opened.close())
        stream = null
        assertNull(backend.diagnostics())
    }

    @Test
    fun `OUT-05 and MET-09 a park keeps the handle with its engine and the next open resumes it, at the device's new rate`() {
        val first = open()
        val handle = native.single
        assertTrue(first.park())
        stream = null
        assertEquals(listOf(Call("park", listOf(ParkReason.HEADPHONES_DISCONNECTED.ordinal))), handle.calls("park"))
        assertTrue(handle.parked)
        assertFalse(handle.destroyed)
        assertNull(backend.diagnostics())
        await("event thread gone") { ownEventThreadGone() }

        native.sampleRate = 44_100
        val second = open()
        assertEquals(1, native.handles.size)
        assertEquals(listOf(Call("resume", emptyList())), handle.calls("resume"))
        assertEquals(listOf(Call("register", listOf(NativeStreamBackend.HOLDER.ordinal))), handle.calls("register"))
        assertTrue(handle.streamOpen)
        assertFalse(handle.parked)
        assertEquals(44_100, second.sampleRate)
        assertEquals(44_100L, backend.diagnostics()?.sampleRate)
        // The park the first stream left behind reached its own listener at most, never the verdict.
        assertTrue(disconnected.all { it === first })
    }

    @Test
    fun `a park after the native side parked itself is nothing more, and the resume still reopens`() {
        val first = open()
        val handle = native.single
        handle.disconnect()
        await("disconnect") { disconnected.isNotEmpty() }
        assertTrue(first.park())
        stream = null
        assertTrue(handle.parked)
        assertFalse(handle.destroyed)
        open()
        assertEquals(1, native.handles.size)
        assertTrue(handle.streamOpen)
    }

    @Test
    fun `a close destroys the handle and the next open creates another, from nothing`() {
        val first = open()
        assertTrue(first.close())
        stream = null
        assertTrue(native.handle(1).destroyed)
        open()
        assertEquals(2, native.handles.size)
        assertEquals(emptyList<Call>(), native.handle(2).calls("resume"))
        assertTrue(native.handle(2).streamOpen)
    }

    @Test
    fun `discard destroys a parked handle so the next open creates another, and with nothing parked it is nothing`() {
        backend.discard()
        assertTrue(native.handles.isEmpty())
        val first = open()
        assertTrue(first.park())
        stream = null
        backend.discard()
        assertTrue(native.handle(1).destroyed)
        open()
        assertEquals(2, native.handles.size)
        assertEquals(emptyList<Call>(), native.handle(2).calls("resume"))
    }

    @Test
    fun `OUT-17 a resume the device refuses fails with the device and the rates and destroys the handle`() {
        val first = open()
        assertTrue(first.park())
        stream = null
        native.refuseOpens = true
        val failure = backend.open(StreamRequest(), listener).exceptionOrNull() as StreamOpenFailure
        assertEquals("Built-in speaker", failure.deviceName)
        assertEquals(listOf(48_000, 44_100), failure.refusedRates)
        assertTrue(native.handle(1).destroyed)
    }

    @Test
    fun `a diagnostics read in flight on another thread finishes before the handle is destroyed`() {
        val opened = open()
        native.holdDiagnostics = true
        val reader = Thread { backend.diagnostics() }
        reader.start()
        await("the reader inside the library") { reader.state == Thread.State.WAITING || reader.state == Thread.State.TIMED_WAITING }

        val closer = Thread { opened.close() }
        closer.start()
        // The close has every reason to finish except the reader; it must wait.
        Thread.sleep(200)
        assertTrue(closer.isAlive)
        assertEquals(emptyList<String>(), native.lifetimeTrace.toList())

        native.releaseDiagnostics()
        reader.join(5_000)
        closer.join(5_000)
        stream = null
        assertEquals(listOf("diagnostics", "destroy"), native.lifetimeTrace.toList())
    }
}
