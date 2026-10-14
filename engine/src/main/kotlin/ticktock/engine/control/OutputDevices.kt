// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import ticktock.engine.output.RoutedDevice

/**
 * The output devices the platform knows about, and the three facts the
 * control thread acts on: a device appeared, a device vanished, and the
 * "becoming noisy" warning that precedes an unplug. Parking never waits on
 * a stream error, because the MMAP streams of API 28 and 29 fire none on
 * unplug.
 */
interface OutputDevices {
    interface Listener {
        fun onAdded(devices: List<RoutedDevice>)
        fun onRemoved(devices: List<RoutedDevice>)
        fun onBecomingNoisy()
    }

    /** Every output device present now. */
    fun outputs(): List<RoutedDevice>

    /** Delivers every callback on [handler]'s thread. */
    fun watch(handler: Handler, listener: Listener)

    fun unwatch()
}

/** The platform implementation: an [AudioDeviceCallback] plus the noisy broadcast. */
class AndroidOutputDevices(private val context: Context, private val audioManager: AudioManager) : OutputDevices {
    private var callback: AudioDeviceCallback? = null
    private var receiver: BroadcastReceiver? = null

    override fun outputs(): List<RoutedDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.toRouted() }

    override fun watch(handler: Handler, listener: OutputDevices.Listener) {
        unwatch()
        val deviceCallback = object : AudioDeviceCallback() {
            // Registration replays the devices already present, and inputs
            // arrive here too. An input is never a route: a USB microphone
            // must not read as a headphone replug and bring parked tools out
            // on the speaker (OUT-03). Only what the output list holds is
            // passed on, and an empty list is not an event.
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) {
                val outputIds = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapTo(HashSet()) { it.id }
                added.filter { it.id in outputIds }.routed().takeIf { it.isNotEmpty() }?.let(listener::onAdded)
            }

            // A removed input matches no stream, so the control thread
            // ignores it by id; the output list has already dropped the
            // device, so there is nothing to filter against here.
            override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) {
                removed.routed().takeIf { it.isNotEmpty() }?.let(listener::onRemoved)
            }
        }
        val noisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) listener.onBecomingNoisy()
            }
        }
        // A protected system broadcast: no export flag applies, and the handler
        // keeps delivery on the control thread.
        context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), null, handler)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        callback = deviceCallback
        receiver = noisyReceiver
    }

    override fun unwatch() {
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        receiver?.let { context.unregisterReceiver(it) }
        callback = null
        receiver = null
    }

    companion object {
        fun AudioDeviceInfo.toRouted(): RoutedDevice = RoutedDevice(id, type, productName.toString())

        private fun Iterable<AudioDeviceInfo>.routed(): List<RoutedDevice> = map { it.toRouted() }

        private fun Array<AudioDeviceInfo>.routed(): List<RoutedDevice> = asIterable().routed()
    }
}
