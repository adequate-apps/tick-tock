// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.input

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord
import ticktock.core.contracts.InputSource
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The platform class against Robolectric's AudioRecord and AudioManager: the
 * stream opens with the preset and rate asked for, float audio comes through
 * unchanged, and the device callback reaches the listener.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidRecorderPlatformTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val platform = AndroidRecorderPlatform(context)
    private val opened = CopyOnWriteArrayList<AudioRecord>()

    @After
    fun clearShadowSource() {
        ShadowAudioRecord.clearSource()
    }

    @Test
    fun `the recorder opens with the preset and rate asked for and reads float audio unchanged`() {
        val rate = 48_000
        val sine = Sine(440.0, 0.1, rate)
        val expected = sine.next(Hop.frames(rate))
        var offset = 0
        ShadowAudioRecord.setSourceProvider { record ->
            opened += record
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInFloatArray(audioData: FloatArray, offsetInFloats: Int, sizeInFloats: Int, isBlocking: Boolean): Int {
                    val count = minOf(sizeInFloats, expected.size - offset)
                    System.arraycopy(expected, offset, audioData, offsetInFloats, count)
                    offset += count
                    return count
                }
            }
        }

        val recorder = (platform.open(InputSource.VOICE_RECOGNITION, rate, null) as? OpenResult.Opened)?.recorder ?: error("the platform refused")
        assertTrue(recorder.start())
        val collected = ArrayList<Float>()
        val chunk = FloatArray(expected.size)
        while (collected.size < expected.size) {
            val got = recorder.read(chunk, expected.size - collected.size)
            if (got <= 0) break
            for (i in 0 until got) collected += chunk[i]
        }
        recorder.stop()
        recorder.release()

        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, opened.single().audioSource)
        assertEquals(rate, opened.single().sampleRate)
        assertEquals(rate, recorder.sampleRate)
        assertEquals(InputSource.VOICE_RECOGNITION, recorder.source)
        assertEquals(AudioRecordSource.DEFAULT_DEVICE_NAME, recorder.device.name)
        assertEquals(expected.toList(), collected)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, opened.single().state)
    }

    @Test
    fun `without the UNPROCESSED property the source asks for VOICE_RECOGNITION first`() {
        ShadowAudioRecord.setSourceProvider { record ->
            opened += record
            object : ShadowAudioRecord.AudioRecordSource {}
        }
        val manager = context.getSystemService(AudioManager::class.java)
        assertTrue(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != "true")
        assertEquals(false, platform.supportsUnprocessed())

        val source = AudioRecordSource(platform)
        val info = source.start { _, _ -> }.getOrThrow()
        source.stop()

        assertEquals(InputSource.VOICE_RECOGNITION, info.source)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, opened.first().audioSource)
        assertTrue(opened.all { it.state == AudioRecord.STATE_UNINITIALIZED })
    }

    @Test
    fun `the device callback reports plugged and unplugged inputs by name`() {
        val added = CopyOnWriteArrayList<InputDevice>()
        val removed = CopyOnWriteArrayList<InputDevice>()
        val handle = platform.watchDevices { a, r ->
            added += a
            removed += r
        }
        val usb = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build()
        val manager = shadowOf(context.getSystemService(AudioManager::class.java))

        manager.addInputDevice(usb, true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        manager.removeInputDevice(usb, true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        handle.close()
        manager.addInputDevice(usb, true)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(1, added.size)
        assertEquals(1, removed.size)
        assertEquals(added.single(), removed.single())
        assertNotNull(platform.inputDevices())
    }
}
