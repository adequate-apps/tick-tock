// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import ticktock.core.contracts.RouteClass

class RouteClassifierTest {
    @Test
    fun `OUT-12 the built-in speakers are the speaker route`() {
        for (type in listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)) {
            assertEquals(RouteClassification(RouteClass.SPEAKER, wireless = false), RouteClassifier.classify(type))
        }
    }

    @Test
    fun `OUT-12 wired, USB and line outputs are headphone-class and wired`() {
        val wired = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
        )
        for (type in wired) {
            assertEquals("type $type", RouteClassification(RouteClass.HEADPHONES, wireless = false), RouteClassifier.classify(type))
        }
    }

    @Test
    fun `OUT-12 Bluetooth classic, LE audio and hearing aids are headphone-class and wireless`() {
        val wireless = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )
        for (type in wireless) {
            assertEquals("type $type", RouteClassification(RouteClass.HEADPHONES, wireless = true), RouteClassifier.classify(type))
        }
    }

    @Test
    fun `OUT-12 outputs that may be a loudspeaker elsewhere are unknown, as is no device at all`() {
        for (type in listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_BUS, AudioDeviceInfo.TYPE_REMOTE_SUBMIX)) {
            assertEquals("type $type", RouteClassifier.UNKNOWN, RouteClassifier.classify(type))
        }
        assertEquals(RouteClassifier.UNKNOWN, RouteClassifier.classify(null as RoutedDevice?))
        assertEquals(
            RouteClassification(RouteClass.HEADPHONES, wireless = true),
            RouteClassifier.classify(RoutedDevice(9, AudioDeviceInfo.TYPE_BLE_HEADSET, "LE earbuds")),
        )
    }
}
