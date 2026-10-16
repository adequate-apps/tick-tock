// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ticktock.app.AppGraph
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputInfo
import ticktock.core.contracts.Voice
import ticktock.core.metro.ClickSound
import ticktock.core.prefs.Prefs
import ticktock.engine.output.NativeDiagnostics
import java.util.Locale

/** Device probes drive and observe the same output owner as the real panes. */
fun installDebugOutput(activity: ComponentActivity, graph: AppGraph) {
    val choice = OutputSeam.choose(activity) ?: return
    graph.output.observeEvent = { Log.i(LOG_TAG, "output event=${it.line()}") }
    graph.output.observeCommand = { Log.i(LOG_TAG, "output sent=$it") }
    graph.prefs.update { prefs ->
        var updated = choice.a4Hz?.let { prefs.copy(a4Hz = it) } ?: prefs
        choice.droneMidi?.let { updated = updated.copy(droneMidi = it, droneVoice = Voice.SINE, droneGain = 0.5f) }
        choice.metroBpm?.let { updated = updated.copy(metroBpm = it, metroBeatsPerBar = 4, metroBeatUnit = 4,
            metroCompoundPulse = false, metroAccents = Prefs.defaultAccentRow(4), metroSubdivision = 1,
            metroBarsOn = 1, metroBarsOff = 0, clickSound = ClickSound.WOOD, clickGain = 0.8f) }
        updated
    }
    choice.droneMidi?.let { graph.output.startDrone() }
    choice.metroBpm?.let { graph.output.startMetro() }
    activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                graph.output.diagnostics()?.let { Log.i(LOG_TAG, "output diagnostics ${it.line()}") }
                delay(DIAGNOSTICS_PERIOD_MILLIS)
            }
        }
    }
}

private fun OutputEvent.line(): String = when (this) {
    is OutputEvent.Opened -> "Opened ${info.line()}"
    is OutputEvent.RoutingChanged -> "RoutingChanged ${info.line()}"
    is OutputEvent.Resumed -> "Resumed tools=$tools ${info.line()}"
    OutputEvent.Closed -> "Closed"
    is OutputEvent.Beat -> "Beat index=$index beatInBar=$beatInBar frame=$frame nanos=$nanos"
    is OutputEvent.Parked -> "Parked reason=$reason tools=$tools"
    is OutputEvent.Failed -> "Failed device=\"$deviceName\" rates=${refusedRates.joinToString(",")}"
    is OutputEvent.DuckSettled -> "DuckSettled generation=$generation frame=$frame nanos=$nanos"
    is OutputEvent.Counters -> "Counters late=$lateBeatsDropped underruns=$underruns"
}

private fun OutputInfo.line(): String =
    "rate=$sampleRate burst=$framesPerBlock device=\"$deviceName\" class=$routeClass latency=$latencyMillis"

private fun NativeDiagnostics.line(): String = String.format(
    Locale.US,
    "frames=%d rate=%d underruns=%d bursts=%d growth=%d late=%d droppedCommands=%d droppedBeats=%d open=%b parked=%b",
    framesRendered, sampleRate, underruns, bufferBursts, growthSteps, lateBeatsDropped, droppedCommands, droppedBeats, streamOpen, parked,
)

private const val LOG_TAG = "ticktock.debug"
private const val DIAGNOSTICS_PERIOD_MILLIS = 1_000L
