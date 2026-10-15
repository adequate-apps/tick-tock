// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.input

import ticktock.core.contracts.InputSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [RecorderPlatform] a test scripts: which presets it refuses, whether an
 * open hangs or is denied, which devices are plugged in. It keeps the set of
 * recorders opened and not yet released, so a test can prove that exactly one
 * exists at a time and none at the end (IN-01).
 */
class FakeRecorderPlatform : RecorderPlatform {
    var unprocessed = true
    val devices = mutableListOf(BUILT_IN)
    val refused = mutableSetOf<Pair<InputSource, Int>>()
    var denied = false
    var busy = false
    var bufferFrames = 0

    /** While true every open blocks until [releaseHung]. */
    @Volatile
    var hangOpens = false
    private val hung = CountDownLatch(1)

    val attempts = mutableListOf<Pair<InputSource, Int>>()
    val openRecorders = LinkedHashSet<FakeRecorder>()
    var maxOpen = 0
        private set
    var promoted = 0
        private set
    private val listeners = mutableListOf<DeviceListener>()

    override fun supportsUnprocessed(): Boolean = unprocessed

    override fun inputDevices(): List<InputDevice> = devices.toList()

    override fun open(source: InputSource, sampleRate: Int, device: InputDevice?): OpenResult {
        synchronized(this) { attempts += source to sampleRate }
        if (hangOpens) hung.await()
        if (denied) return OpenResult.Denied
        if (source to sampleRate in refused) return OpenResult.Refused
        val recorder = FakeRecorder(this, sampleRate, source, device ?: BUILT_IN, busy)
        synchronized(this) {
            openRecorders += recorder
            maxOpen = maxOf(maxOpen, openRecorders.size)
        }
        return OpenResult.Opened(recorder)
    }

    override fun watchDevices(listener: DeviceListener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun promoteCaptureThread() {
        promoted++
    }

    fun releaseHung() = hung.countDown()

    fun plug(device: InputDevice) {
        devices += device
        listeners.toList().forEach { it.onDevicesChanged(listOf(device), emptyList()) }
    }

    fun unplug(device: InputDevice) {
        devices -= device
        listeners.toList().forEach { it.onDevicesChanged(emptyList(), listOf(device)) }
    }

    fun released(recorder: FakeRecorder) {
        synchronized(this) { openRecorders -= recorder }
    }

    val lastOpened: FakeRecorder get() = synchronized(this) { openRecorders.last() }

    companion object {
        val BUILT_IN = InputDevice(1, AudioRecordSource.DEFAULT_DEVICE_NAME, isBuiltIn = true)
        val USB = InputDevice(7, "USB interface", isBuiltIn = false)
    }
}

/**
 * A recorder fed by hand. [push] queues audio that [read] returns in order;
 * a read with nothing queued blocks until something arrives, the way a real
 * blocking read waits for the hardware. [fail] makes the next read return an
 * error; [stopBehindOurBack] makes reads return zero frames, the shape of a
 * recorder that was stopped and never restarted (IN-05).
 */
class FakeRecorder(
    private val platform: FakeRecorderPlatform,
    override val sampleRate: Int,
    override val source: InputSource,
    override val device: InputDevice,
    private val refuseStart: Boolean,
) : Recorder {
    override val bufferFrames: Int get() = platform.bufferFrames
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val pending = ArrayDeque<Float>()
    private var failing = false
    private var recording = false
    private var released = false
    var releases = 0
        private set
    var listener: ((RecordingState) -> Unit)? = null
        private set

    override fun start(): Boolean {
        if (refuseStart) return false
        recording = true
        return true
    }

    override fun read(into: FloatArray, frames: Int): Int {
        lock.lock()
        try {
            while (true) {
                if (failing || released) return -1
                if (!recording) return 0
                if (pending.isNotEmpty()) {
                    val count = minOf(frames, pending.size)
                    for (i in 0 until count) into[i] = pending.removeFirst()
                    return count
                }
                arrived.await(READ_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            }
        } finally {
            lock.unlock()
        }
    }

    override fun stop() = lock.withLock {
        recording = false
        arrived.signalAll()
    }

    override fun release() {
        lock.withLock {
            released = true
            releases++
            arrived.signalAll()
        }
        platform.released(this)
    }

    override fun setRecordingListener(listener: ((RecordingState) -> Unit)?) {
        this.listener = listener
    }

    fun push(samples: FloatArray) = lock.withLock {
        samples.forEach { pending.addLast(it) }
        arrived.signalAll()
    }

    fun fail() = lock.withLock {
        failing = true
        arrived.signalAll()
    }

    fun stopBehindOurBack() = stop()

    fun report(state: RecordingState) {
        listener?.invoke(state)
    }

    companion object {
        private const val READ_WAIT_MILLIS = 50L
    }
}
