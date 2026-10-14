// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ticktock.core.contracts.AudioInput
import ticktock.core.contracts.AudioSink
import ticktock.core.contracts.InputException
import ticktock.core.contracts.InputFailure
import ticktock.core.contracts.InputInfo
import ticktock.core.contracts.InputSource
import ticktock.core.detect.Hop
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Hears about the stream between [AudioInput.start] and [AudioInput.stop].
 * Every call arrives on one of the source's own threads; a caller that
 * updates a screen posts to it. The default does nothing.
 */
interface InputListener {
    /** The running stream died and the recorder was released; nothing restarts unless a device returns. */
    fun onStopped(failure: InputFailure) {}

    /** A device came back and the stream is running again on it (IN-04). */
    fun onResumed(info: InputInfo) {}

    /** The saved interface was absent when the stream opened, so another microphone is in use (IN-02, IN-03). */
    fun onDeviceAbsent(failure: InputFailure.Disconnected) {}

    object None : InputListener
}

/**
 * The production [AudioInput]. Opens the microphone through a
 * [RecorderPlatform] and delivers float blocks to the sink from a thread
 * named `capture` that does nothing but block in the recorder's read.
 *
 * The preset is chosen by the platform property, so a device without an
 * UNPROCESSED path is asked for VOICE_RECOGNITION first and never opens a
 * processed stream under the wrong name (SRC-01). Every attempt in the chain
 * shares one deadline, so a driver that hangs costs one timeout in total
 * rather than one per rate (IN-06).
 *
 * A failed or dying recorder is released where it was found (IN-01) and the
 * sink gets nothing it did not record, so a "no audio" gate behind this
 * source means real silence (IN-05). The device callback restarts the stream
 * when a device returns (IN-04). The saved interface is read and never
 * written here: when it is absent the platform default serves this session
 * and the listener is told which (IN-03).
 */
class AudioRecordSource(
    private val platform: RecorderPlatform,
    /** The player's saved interface by name, or null for the platform default. */
    private val savedDeviceName: String? = null,
    private val listener: InputListener = InputListener.None,
    private val openTimeoutMillis: Long = OPEN_TIMEOUT_MILLIS,
) : AudioInput {
    private val lock = Any()

    /** The open stream; guarded by [lock]. */
    private var current: Stream? = null

    /** The sink the client wants fed; set by start, cleared by stop, kept across a disconnect so a replug can resume. */
    private var wantedSink: AudioSink? = null
    private var watch: AutoCloseable? = null

    private val recordingStateFlow = MutableStateFlow<RecordingState?>(null)

    /** The platform's report on the running capture, null while closed (SRC-02, CAL-05). */
    val recordingState: StateFlow<RecordingState?> = recordingStateFlow

    /** What is open now, null while closed. */
    @Volatile
    var info: InputInfo? = null
        private set

    /** Set when the saved interface was absent at the last open; null when it was used or none is saved. */
    @Volatile
    var absentDevice: InputFailure.Disconnected? = null
        private set

    /** Frames handed to the sink since the stream started: the capture clock. */
    @Volatile
    var framesDelivered: Long = 0
        private set

    override fun start(sink: AudioSink): Result<InputInfo> {
        // A second start replaces the stream (POL-17): close before open, so exactly one recorder exists.
        val replaced = synchronized(lock) { closeLocked() }
        replaced?.join()
        val result = synchronized(lock) {
            wantedSink = sink
            val opened = openLocked(sink)
            if (opened.isSuccess) {
                if (watch == null) watch = platform.watchDevices(::onDevicesChanged)
            } else {
                wantedSink = null
            }
            opened
        }
        if (result.isSuccess) absentDevice?.let(listener::onDeviceAbsent)
        return result
    }

    override fun stop() {
        val closed: Stream?
        synchronized(lock) {
            wantedSink = null
            closed = closeLocked()
            watch?.close()
            watch = null
        }
        closed?.join()
    }

    private fun openLocked(sink: AudioSink): Result<InputInfo> {
        val devices = platform.inputDevices()
        val saved = savedDeviceName
        val chosen = saved?.let { name -> devices.firstOrNull { it.name == name } }
        absentDevice = if (saved != null && chosen == null) InputFailure.Disconnected(saved) else null
        val recorder = openChain(chosen).getOrElse { return Result.failure(it) }
        recorder.setRecordingListener { state -> recordingStateFlow.value = state }
        if (!recorder.start()) {
            // Before API 29 a second capturer is refused here; from 29 the platform silences it instead (CAL-05).
            recorder.release()
            return Result.failure(InputException(InputFailure.Busy(recorder.device.name)))
        }
        val opened = InputInfo(recorder.sampleRate, recorder.device.name, recorder.source)
        val stream = Stream(recorder, sink)
        current = stream
        info = opened
        framesDelivered = 0
        stream.thread.start()
        return Result.success(opened)
    }

    /** Closes the open stream, if any, and returns it for the caller to join outside the lock. */
    private fun closeLocked(): Stream? {
        val stream = current ?: return null
        current = null
        info = null
        recordingStateFlow.value = null
        stream.close()
        return stream
    }

    /**
     * Tries every preset the property allows at every rate until one opens,
     * under one deadline for the whole chain (IN-06).
     */
    private fun openChain(device: InputDevice?): Result<Recorder> {
        val sources = if (platform.supportsUnprocessed()) {
            listOf(InputSource.UNPROCESSED, InputSource.VOICE_RECOGNITION, InputSource.MIC)
        } else {
            listOf(InputSource.VOICE_RECOGNITION, InputSource.MIC)
        }
        val deviceName = device?.name ?: DEFAULT_DEVICE_NAME
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(openTimeoutMillis)
        val tried = LinkedHashSet<Int>()
        for (source in sources) {
            for (rate in RATES) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return refused(deviceName, tried)
                tried += rate
                when (val outcome = OpenAttempt { platform.open(source, rate, device) }.await(remaining)) {
                    is OpenAttempt.Opened -> return Result.success(outcome.recorder)
                    OpenAttempt.Refused -> continue
                    OpenAttempt.Denied -> return Result.failure(InputException(InputFailure.Denied(deviceName)))
                    OpenAttempt.Hung -> return refused(deviceName, tried)
                }
            }
        }
        return refused(deviceName, tried)
    }

    private fun refused(deviceName: String, rates: Set<Int>): Result<Recorder> =
        Result.failure(InputException(InputFailure.Refused(deviceName, rates.toList())))

    private fun onDevicesChanged(added: List<InputDevice>, removed: List<InputDevice>) {
        var stopped: InputFailure? = null
        var resumed: InputInfo? = null
        var toJoin: Stream? = null
        synchronized(lock) {
            val sink = wantedSink ?: return
            current?.let { running ->
                if (removed.any { it.id == running.recorder.device.id }) {
                    // The read loop would fail on its own, but a read blocked on a device that
                    // no longer exists may never return; close it here and say why.
                    toJoin = closeLocked()
                    stopped = InputFailure.Disconnected(running.recorder.device.name)
                }
            }
            val saved = savedDeviceName
            val savedReturned = saved != null && added.any { it.name == saved }
            val onFallback = saved != null && current?.let { it.recorder.device.name != saved } == true
            if (current == null && added.isNotEmpty()) {
                // A device came back: the stream resumes on the best one present (IN-04).
                openLocked(sink).onSuccess { resumed = it }
            } else if (savedReturned && onFallback) {
                // The saved interface is back: move to it, closing first so one recorder exists at a time.
                toJoin = closeLocked()
                openLocked(sink).onSuccess { resumed = it }
            }
        }
        toJoin?.join()
        stopped?.let(listener::onStopped)
        resumed?.let(listener::onResumed)
    }

    /** One open call on its own thread, so a platform that hangs leaves the caller free to give up. */
    private class OpenAttempt(private val open: () -> OpenResult) {
        sealed interface Outcome
        class Opened(val recorder: Recorder) : Outcome
        object Refused : Outcome
        object Denied : Outcome
        object Hung : Outcome

        private val handoff = AtomicReference<Any?>(PENDING)
        private val done = CountDownLatch(1)

        fun await(nanos: Long): Outcome {
            Thread({
                val produced: Any? = try {
                    open()
                } catch (refused: RuntimeException) {
                    refused
                }
                // The caller gave up: whatever the late call produced is released here, never leaked (IN-01).
                if (!handoff.compareAndSet(PENDING, produced)) (produced as? OpenResult.Opened)?.recorder?.release()
                done.countDown()
            }, "capture-open").apply { isDaemon = true }.start()
            if (!done.await(nanos, TimeUnit.NANOSECONDS) && handoff.compareAndSet(PENDING, ABANDONED)) return Hung
            return when (val produced = handoff.get()) {
                is OpenResult.Opened -> Opened(produced.recorder)
                OpenResult.Denied -> Denied
                else -> Refused
            }
        }

        private companion object {
            val PENDING = Any()
            val ABANDONED = Any()
        }
    }

    /** One running capture: the recorder, the sink and the thread that joins them. */
    private inner class Stream(val recorder: Recorder, private val sink: AudioSink) {
        @Volatile
        private var running = true
        val thread = Thread(::run, THREAD_NAME)

        fun close() {
            running = false
            recorder.stop()
            recorder.release()
        }

        fun join() {
            if (Thread.currentThread() !== thread) thread.join(JOIN_MILLIS)
        }

        private fun run() {
            platform.promoteCaptureThread()
            val block = FloatArray(Hop.frames(recorder.sampleRate) / BLOCKS_PER_HOP)
            var emptyReads = 0
            while (running) {
                val frames = recorder.read(block, block.size)
                if (frames > 0) {
                    emptyReads = 0
                    framesDelivered += frames
                    sink(block, frames)
                    continue
                }
                if (!running) break
                // A recorder that returns nothing is dead or stopped behind our back; the sink
                // never gets zeros it did not record, so a "no audio" gate stays honest (IN-05).
                if (frames < 0 || ++emptyReads >= DEAD_READS) {
                    died()
                    break
                }
            }
        }

        private fun died() {
            synchronized(lock) {
                if (current !== this) return
                current = null
                info = null
                recordingStateFlow.value = null
            }
            close()
            listener.onStopped(InputFailure.Disconnected(recorder.device.name))
        }
    }

    companion object {
        const val THREAD_NAME = "capture"

        /** Asked for in this order; the first the platform grants is reported in [InputInfo.sampleRate]. */
        val RATES = listOf(48_000, 44_100)

        /** One deadline for the whole open chain (IN-06). */
        const val OPEN_TIMEOUT_MILLIS = 2_000L

        /** Every input device needs a name in a status sentence; the platform default is the built-in path. */
        const val DEFAULT_DEVICE_NAME = "Built-in microphone"

        /** Four reads per hop keep the capture thread's own latency under a quarter hop. */
        const val BLOCKS_PER_HOP = 4

        /** Consecutive empty reads before a recorder is declared dead; each returns at once, so this is instant. */
        const val DEAD_READS = 8

        private const val JOIN_MILLIS = 1_000L
    }
}
