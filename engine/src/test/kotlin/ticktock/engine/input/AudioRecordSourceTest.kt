// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.input

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.InputException
import ticktock.core.contracts.InputFailure
import ticktock.core.contracts.InputInfo
import ticktock.core.contracts.InputSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Spec 7.5 and the SRC criteria on the scripted platform: the open chain,
 * the shared timeout, release on every failure, the device callback and the
 * recording report.
 */
class AudioRecordSourceTest {
    private val platform = FakeRecorderPlatform()
    private val stopped = CopyOnWriteArrayList<InputFailure>()
    private val resumed = CopyOnWriteArrayList<InputInfo>()
    private val absent = CopyOnWriteArrayList<InputFailure.Disconnected>()
    private val delivered = CopyOnWriteArrayList<FloatArray>()
    private val listener = object : InputListener {
        override fun onStopped(failure: InputFailure) {
            stopped += failure
        }

        override fun onResumed(info: InputInfo) {
            resumed += info
        }

        override fun onDeviceAbsent(failure: InputFailure.Disconnected) {
            absent += failure
        }
    }
    private var source: AudioRecordSource? = null

    /** A fresh source; [live] is the one made last. */
    private fun source(savedDevice: String? = null, timeoutMillis: Long = 5_000) =
        AudioRecordSource(platform, savedDevice, listener, timeoutMillis).also { source = it }

    private val live: AudioRecordSource get() = source ?: error("no source yet")

    private fun sink(samples: FloatArray, frames: Int) {
        delivered += samples.copyOf(frames)
    }

    @After
    fun stopSource() {
        source?.stop()
    }

    @Test
    fun `SRC-01 the UNPROCESSED property decides the first preset asked for`() {
        platform.unprocessed = true
        val info = source().start(::sink).getOrThrow()
        assertEquals(InputSource.UNPROCESSED to 48_000, platform.attempts.first())
        assertEquals(InputSource.UNPROCESSED, info.source)
        assertEquals(48_000, info.sampleRate)
        live.stop()

        platform.attempts.clear()
        platform.unprocessed = false
        val fallback = source().start(::sink).getOrThrow()
        assertEquals(InputSource.VOICE_RECOGNITION to 48_000, platform.attempts.first())
        assertEquals(InputSource.VOICE_RECOGNITION, fallback.source)
        assertTrue("UNPROCESSED is never asked for without the property", platform.attempts.none { it.first == InputSource.UNPROCESSED })
    }

    @Test
    fun `SRC-01 a refused preset falls through the chain at every rate and the opened one is reported`() {
        platform.refused += InputSource.UNPROCESSED to 48_000
        platform.refused += InputSource.UNPROCESSED to 44_100
        platform.refused += InputSource.VOICE_RECOGNITION to 48_000

        val info = source().start(::sink).getOrThrow()

        assertEquals(
            listOf(
                InputSource.UNPROCESSED to 48_000,
                InputSource.UNPROCESSED to 44_100,
                InputSource.VOICE_RECOGNITION to 48_000,
                InputSource.VOICE_RECOGNITION to 44_100,
            ),
            platform.attempts,
        )
        assertEquals(InputInfo(44_100, AudioRecordSource.DEFAULT_DEVICE_NAME, InputSource.VOICE_RECOGNITION), info)
        assertEquals(1, platform.openRecorders.size)
    }

    @Test
    fun `SRC-01 every preset refused is one Refused failure naming the device and the rates`() {
        for (preset in listOf(InputSource.UNPROCESSED, InputSource.VOICE_RECOGNITION, InputSource.MIC)) {
            for (rate in AudioRecordSource.RATES) platform.refused += preset to rate
        }

        val failure = source().start(::sink).failure()

        assertEquals(InputFailure.Refused(AudioRecordSource.DEFAULT_DEVICE_NAME, listOf(48_000, 44_100)), failure)
        assertEquals(6, platform.attempts.size)
        assertTrue(platform.openRecorders.isEmpty())
    }

    @Test
    fun `IN-06 an open that hangs costs one timeout for the whole chain and the late recorder is released`() {
        platform.hangOpens = true
        val timeout = 150L

        val started = System.nanoTime()
        val failure = source(timeoutMillis = timeout).start(::sink).failure()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(InputFailure.Refused(AudioRecordSource.DEFAULT_DEVICE_NAME, listOf(48_000)), failure)
        assertTrue("spent $elapsed ms on a $timeout ms timeout", elapsed < timeout * 2)
        assertEquals("one attempt in flight, never one per rate", 1, platform.attempts.size)

        // The driver eventually answers: what it hands back belongs to nobody and is released (IN-01).
        platform.hangOpens = false
        platform.releaseHung()
        awaitUntil("late recorder released") { platform.openRecorders.isEmpty() && platform.attempts.size == 1 }
    }

    @Test
    fun `IN-02 a busy microphone is reported as Busy naming the device, and the recorder is released`() {
        platform.busy = true

        val failure = source().start(::sink).failure()

        assertEquals(InputFailure.Busy(AudioRecordSource.DEFAULT_DEVICE_NAME), failure)
        assertTrue(platform.openRecorders.isEmpty())
        assertNull(live.info)
    }

    @Test
    fun `IN-02 IN-03 a saved interface that is absent is reported as Disconnected and the session uses the built-in microphone`() {
        val info = source(savedDevice = FakeRecorderPlatform.USB.name).start(::sink).getOrThrow()

        assertEquals(AudioRecordSource.DEFAULT_DEVICE_NAME, info.deviceName)
        assertEquals(listOf(InputFailure.Disconnected("USB interface")), absent)
        assertEquals(InputFailure.Disconnected("USB interface"), live.absentDevice)
    }

    @Test
    fun `IN-03 the saved interface is used when present and the choice itself is never rewritten`() {
        platform.devices += FakeRecorderPlatform.USB
        val saved = FakeRecorderPlatform.USB.name

        val info = source(savedDevice = saved).start(::sink).getOrThrow()

        assertEquals("USB interface", info.deviceName)
        assertEquals(FakeRecorderPlatform.USB, platform.lastOpened.device)
        assertNull(live.absentDevice)
        assertTrue(absent.isEmpty())
        live.stop()

        // Absent next time: the fallback serves, and the name the source was given is what it looks for again.
        platform.devices -= FakeRecorderPlatform.USB
        assertEquals(AudioRecordSource.DEFAULT_DEVICE_NAME, source(savedDevice = saved).start(::sink).getOrThrow().deviceName)
        live.stop()
        platform.devices += FakeRecorderPlatform.USB
        assertEquals("USB interface", source(savedDevice = saved).start(::sink).getOrThrow().deviceName)
    }

    @Test
    fun `IN-01 a recorder that errors mid-stream is released, the failure names the device, and nothing was fabricated`() {
        source().start(::sink).getOrThrow()
        val recorder = platform.lastOpened
        recorder.push(FloatArray(100) { 0.5f })
        awaitUntil("audio delivered") { delivered.sumOf { it.size } == 100 }

        recorder.fail()

        awaitUntil("stream stopped") { stopped.isNotEmpty() }
        assertEquals(listOf<InputFailure>(InputFailure.Disconnected(AudioRecordSource.DEFAULT_DEVICE_NAME)), stopped)
        assertTrue(platform.openRecorders.isEmpty())
        assertEquals(1, recorder.releases)
        assertNull(live.info)
        assertEquals(100, delivered.sumOf { it.size })
        assertTrue(delivered.all { block -> block.all { it == 0.5f } })
    }

    @Test
    fun `IN-05 a recorder stopped behind our back is declared dead rather than left silent behind the gate`() {
        source().start(::sink).getOrThrow()
        val recorder = platform.lastOpened

        recorder.stopBehindOurBack()

        awaitUntil("stream stopped") { stopped.isNotEmpty() }
        assertEquals(InputFailure.Disconnected(AudioRecordSource.DEFAULT_DEVICE_NAME), stopped.single())
        assertTrue(platform.openRecorders.isEmpty())
        assertTrue("no block was invented", delivered.isEmpty())
    }

    @Test
    fun `IN-01 POL-17 a restart replaces the stream and at no point are two recorders open`() {
        val first = source().start(::sink).getOrThrow()
        val firstRecorder = platform.lastOpened
        val second = live.start(::sink).getOrThrow()

        assertEquals(first, second)
        assertEquals(1, firstRecorder.releases)
        assertEquals(1, platform.openRecorders.size)
        assertEquals(1, platform.maxOpen)
        live.stop()
        assertTrue(platform.openRecorders.isEmpty())
        assertEquals(1, platform.maxOpen)
    }

    @Test
    fun `IN-04 unplugging the interface in use stops the stream and plugging it back resumes on it`() {
        platform.devices += FakeRecorderPlatform.USB
        val info = source(savedDevice = FakeRecorderPlatform.USB.name).start(::sink).getOrThrow()
        assertEquals("USB interface", info.deviceName)

        platform.unplug(FakeRecorderPlatform.USB)

        assertEquals(InputFailure.Disconnected("USB interface"), stopped.single())
        assertTrue(platform.openRecorders.isEmpty())
        assertNull(live.info)

        platform.plug(FakeRecorderPlatform.USB)

        assertEquals(info, resumed.single())
        assertEquals(info, live.info)
        platform.lastOpened.push(FloatArray(64) { 0.25f })
        awaitUntil("audio flows again to the same sink") { delivered.sumOf { it.size } == 64 }
    }

    @Test
    fun `IN-04 a saved interface that returns while the built-in microphone serves takes over`() {
        val info = source(savedDevice = FakeRecorderPlatform.USB.name).start(::sink).getOrThrow()
        assertEquals(AudioRecordSource.DEFAULT_DEVICE_NAME, info.deviceName)
        val builtIn = platform.lastOpened

        platform.plug(FakeRecorderPlatform.USB)

        assertEquals("USB interface", resumed.single().deviceName)
        assertEquals(1, builtIn.releases)
        assertEquals(1, platform.openRecorders.size)
        assertEquals(1, platform.maxOpen)
    }

    @Test
    fun `IN-04 after stop nothing resumes and the device callback is unregistered`() {
        platform.devices += FakeRecorderPlatform.USB
        source(savedDevice = FakeRecorderPlatform.USB.name).start(::sink).getOrThrow()
        live.stop()

        platform.unplug(FakeRecorderPlatform.USB)
        platform.plug(FakeRecorderPlatform.USB)

        assertTrue(stopped.isEmpty())
        assertTrue(resumed.isEmpty())
        assertTrue(platform.openRecorders.isEmpty())
    }

    @Test
    fun `no permission is a Denied failure, not a retry`() {
        platform.denied = true

        val failure = source().start(::sink).failure()

        assertEquals(InputFailure.Denied(AudioRecordSource.DEFAULT_DEVICE_NAME), failure)
        assertEquals(1, platform.attempts.size)
    }

    @Test
    fun `SRC-02 CAL-05 the platform's recording report reaches the state flow and clears on stop`() {
        source().start(::sink).getOrThrow()
        val recorder = platform.lastOpened
        val report = RecordingState(InputSource.VOICE_RECOGNITION, listOf("Automatic Gain Control"), silenced = true, reported = true)

        recorder.report(report)

        assertEquals(report, live.recordingState.value)
        live.stop()
        assertNull(live.recordingState.value)
    }

    @Test
    fun `the capture thread is promoted and counts the frames it delivers`() {
        source().start(::sink).getOrThrow()
        platform.lastOpened.push(FloatArray(300))

        awaitUntil("delivered") { live.framesDelivered == 300L }
        assertEquals(1, platform.promoted)
    }

    private fun Result<InputInfo>.failure(): InputFailure =
        (exceptionOrNull() as? InputException)?.failure ?: error("expected a typed failure, got $this")

    private fun awaitUntil(what: String, timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(5)
        }
    }
}
