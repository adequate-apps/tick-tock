// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import ticktock.engine.output.RoutedDevice

/** The thin platform adapters, against Robolectric's AudioManager. */
@RunWith(RobolectricTestRunner::class)
class AndroidAdaptersTest {
    private val context = RuntimeEnvironment.getApplication()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `CAL-03 the mode adapter reads the mode and hears changes through the platform listener`() {
        val modes = AndroidAudioModes(audioManager)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, modes.current())

        val heard = mutableListOf<Int>()
        modes.listen({ handler.post(it) }) { heard += it }
        audioManager.mode = AudioManager.MODE_RINGTONE
        idle()
        assertEquals(listOf(AudioManager.MODE_RINGTONE), heard)

        modes.unlisten()
        audioManager.mode = AudioManager.MODE_NORMAL
        idle()
        assertEquals(listOf(AudioManager.MODE_RINGTONE), heard)
    }

    @Test
    fun `OUT-07 the device adapter lists outputs, hears late devices and the noisy broadcast`() {
        val devices = AndroidOutputDevices(context, audioManager)
        val added = mutableListOf<List<RoutedDevice>>()
        val removed = mutableListOf<List<RoutedDevice>>()
        var noisy = 0
        devices.watch(
            handler,
            object : OutputDevices.Listener {
                override fun onAdded(devices: List<RoutedDevice>) { added += devices }
                override fun onRemoved(devices: List<RoutedDevice>) { removed += devices }
                override fun onBecomingNoisy() { noisy++ }
            },
        )

        val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADSET).build()
        // Registration may replay the devices already present; the headset's
        // own arrival is the last list delivered.
        shadowOf(audioManager).addOutputDevice(headset, true)
        idle()
        assertEquals(listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET), added.last().map { it.type })
        assertTrue(devices.outputs().any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET })
        val addedBeforeUnwatch = added.size

        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertEquals(1, noisy)

        shadowOf(audioManager).removeOutputDevice(headset, true)
        idle()
        assertEquals(1, removed.size)

        devices.unwatch()
        shadowOf(audioManager).addOutputDevice(headset, true)
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertEquals(addedBeforeUnwatch, added.size)
        assertEquals(1, noisy)
    }
}
