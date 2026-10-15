// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.input

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Process
import ticktock.core.contracts.InputSource
import ticktock.core.detect.Hop
import java.util.concurrent.Executor

/**
 * The [RecorderPlatform] on a device: AudioManager for the property, the
 * device list and the device callback; AudioRecord for the stream. Nothing in
 * here decides anything; the decisions live in [AudioRecordSource] where the
 * JVM tests can reach them.
 *
 * [callbackHandler] receives the device callback; null means the main
 * looper, the control thread passes its own.
 */
class AndroidRecorderPlatform(
    context: Context,
    private val callbackHandler: Handler? = null,
) : RecorderPlatform {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * The compatibility definition says an unsupported device answers null;
     * the framework in fact answers the string "true" or "false" from its
     * device configuration. Only an affirmative answer opens UNPROCESSED, so
     * both shapes of "no" lead to VOICE_RECOGNITION (SRC-01).
     */
    override fun supportsUnprocessed(): Boolean =
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)?.toBoolean() == true

    override fun inputDevices(): List<InputDevice> = inputDeviceInfos().map { it.toInputDevice() }

    override fun open(source: InputSource, sampleRate: Int, device: InputDevice?): OpenResult {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source.platformSource())
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes(sampleRate))
                .build()
        } catch (refused: UnsupportedOperationException) {
            return OpenResult.Refused
        } catch (refused: IllegalArgumentException) {
            return OpenResult.Refused
        } catch (denied: SecurityException) {
            // RECORD_AUDIO is asked for by the screen; without it no preset opens, so the chain ends here.
            return OpenResult.Denied
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return OpenResult.Refused
        }
        if (device != null) {
            val info = inputDeviceInfos().firstOrNull { it.id == device.id }
            if (info == null || !record.setPreferredDevice(info)) {
                record.release()
                return OpenResult.Refused
            }
        }
        return OpenResult.Opened(AndroidRecorder(record, source, device ?: builtInDevice()))
    }

    override fun watchDevices(listener: DeviceListener): AutoCloseable {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                // Only inputs can resume a capture; the list is the platform's own at this moment.
                val inputs = inputDeviceInfos().map { it.id }.toSet()
                listener.onDevicesChanged(addedDevices.filter { it.id in inputs }.map { it.toInputDevice() }, emptyList())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                // A removed device is matched by id against the one in use, so no filtering is needed here.
                listener.onDevicesChanged(emptyList(), removedDevices.map { it.toInputDevice() })
            }
        }
        audioManager.registerAudioDeviceCallback(callback, callbackHandler)
        return AutoCloseable { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    override fun promoteCaptureThread() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    }

    private fun inputDeviceInfos(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    private fun builtInDevice(): InputDevice =
        inputDeviceInfos().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.toInputDevice()
            ?: InputDevice(id = 0, name = AudioRecordSource.DEFAULT_DEVICE_NAME, isBuiltIn = true)

    /**
     * Twice the platform minimum or two hops, whichever is larger: the capture
     * thread reads a quarter hop at a time, so a late wake-up costs nothing.
     */
    private fun bufferBytes(sampleRate: Int): Int {
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val twoHops = Hop.windowFrames(sampleRate) * Float.SIZE_BYTES
        return maxOf(if (minimum > 0) minimum * 2 else 0, twoHops)
    }

    companion object {
        fun InputSource.platformSource(): Int = when (this) {
            InputSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
            InputSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            InputSource.MIC, InputSource.FAKE -> MediaRecorder.AudioSource.MIC
        }

        fun sourceFromPlatform(audioSource: Int): InputSource? = when (audioSource) {
            MediaRecorder.AudioSource.UNPROCESSED -> InputSource.UNPROCESSED
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> InputSource.VOICE_RECOGNITION
            MediaRecorder.AudioSource.MIC -> InputSource.MIC
            else -> null
        }

        fun AudioDeviceInfo.toInputDevice(): InputDevice = InputDevice(
            id = id,
            name = if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) AudioRecordSource.DEFAULT_DEVICE_NAME else productName.toString(),
            isBuiltIn = type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
        )
    }
}

/** One AudioRecord. Released exactly once; a second release is a no-op. */
class AndroidRecorder(
    private val record: AudioRecord,
    override val source: InputSource,
    override val device: InputDevice,
) : Recorder {
    override val sampleRate: Int = record.sampleRate
    override val bufferFrames: Int get() = record.bufferSizeInFrames

    private var callback: AudioManager.AudioRecordingCallback? = null

    @Volatile
    private var released = false

    override fun start(): Boolean {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false
        // The platform reports the configuration on changes only; read the first one by hand.
        // Before the first callback some platforms answer with an error rather than null.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { record.activeRecordingConfiguration }.getOrNull()?.let { report(it) }
        }
        return true
    }

    override fun read(into: FloatArray, frames: Int): Int {
        if (released) return AudioRecord.ERROR_DEAD_OBJECT
        return record.read(into, 0, frames, AudioRecord.READ_BLOCKING)
    }

    override fun stop() {
        if (released) return
        if (record.state == AudioRecord.STATE_INITIALIZED) record.stop()
    }

    override fun release() {
        if (released) return
        released = true
        callback?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) record.unregisterAudioRecordingCallback(it) }
        callback = null
        record.release()
    }

    override fun setRecordingListener(listener: ((RecordingState) -> Unit)?) {
        this.listener = listener
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // API 28 reports nothing; say so rather than claiming a clean path.
            listener?.invoke(RecordingState(source, emptyList(), silenced = false, reported = false))
            return
        }
        if (listener == null) {
            callback?.let { record.unregisterAudioRecordingCallback(it) }
            callback = null
            return
        }
        if (callback == null) {
            // Registered before startRecording(), as the platform guide requires, so the first report is not missed.
            val registered = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    configs.firstOrNull()?.let { report(it) }
                }
            }
            record.registerAudioRecordingCallback(DirectExecutor, registered)
            callback = registered
        }
    }

    @Volatile
    private var listener: ((RecordingState) -> Unit)? = null

    private fun report(config: AudioRecordingConfiguration) {
        // The configuration's fields are API 29; callers are guarded, and this keeps the method self-evidently so.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val state = RecordingState(
            source = AndroidRecorderPlatform.sourceFromPlatform(config.audioSource) ?: source,
            effects = config.effects.map { it.name },
            silenced = config.isClientSilenced,
            reported = true,
        )
        listener?.invoke(state)
    }

    /** The callback only copies three fields into a flow, so it runs where the platform calls it. */
    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
