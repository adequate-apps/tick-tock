// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import android.media.AudioDeviceInfo
import ticktock.core.contracts.RouteClass

/**
 * The device a stream is actually playing through, as the platform describes
 * it: [type] is one of the `AudioDeviceInfo.TYPE_*` constants and [name] its
 * product name, for status sentences and error messages (OUT-17).
 */
data class RoutedDevice(val id: Int, val type: Int, val name: String)

/**
 * What the route means to the app. [routeClass] is the contract's view;
 * [wireless] marks the Bluetooth family, whose latency is a first guess from
 * timestamps until the player calibrates by tapping (BTC-01).
 */
data class RouteClassification(val routeClass: RouteClass, val wireless: Boolean)

/**
 * Classifies a route from the routed device and nothing else (OUT-12). The
 * headphone class is every private listening route: wired and USB, Bluetooth
 * classic, LE audio and hearing aids. Outputs that may well be a loudspeaker
 * somewhere else in the room (HDMI, docks, a bus) are unknown, so a replug
 * never resumes onto them and the speaker-route rules never apply to them
 * either.
 */
object RouteClassifier {
    val UNKNOWN = RouteClassification(RouteClass.UNKNOWN, wireless = false)

    fun classify(device: RoutedDevice?): RouteClassification =
        if (device == null) UNKNOWN else classify(device.type)

    fun classify(type: Int): RouteClassification = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        -> RouteClassification(RouteClass.SPEAKER, wireless = false)

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        -> RouteClassification(RouteClass.HEADPHONES, wireless = false)

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> RouteClassification(RouteClass.HEADPHONES, wireless = true)

        else -> UNKNOWN
    }
}
