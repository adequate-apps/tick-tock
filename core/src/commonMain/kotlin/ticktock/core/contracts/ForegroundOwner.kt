// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

/**
 * Hook points for background playback (BGH-01). The output registry calls
 * [onToolsStarted] when it goes from no registered tool to one, before the
 * stream opens, and [onToolsStopped] when it goes from one to none, after the
 * stream closes. Those two transitions are the only places a media-playback
 * foreground service may start and stop: Android requires the service to
 * start while the app is in the foreground, and a tool starting is the one
 * moment that is guaranteed.
 *
 * 2.0 wires [NoForegroundOwner]; v1.1 wires the service and its media
 * notification without touching the registry.
 */
interface ForegroundOwner {
    fun onToolsStarted()
    fun onToolsStopped()
}

/** 2.0: no service exists, so both transitions do nothing. */
object NoForegroundOwner : ForegroundOwner {
    override fun onToolsStarted() = Unit
    override fun onToolsStopped() = Unit
}
