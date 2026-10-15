// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.debug

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.util.Log
import ticktock.app.AppGraph

/**
 * Debug builds only: when the launch carries [InputSeam]'s extras, the Tune
 * session listens to that input instead of the microphone (a synthesized
 * tone needs no permission), and the extra's A4 becomes the saved A4, so
 * the device scripts and the emulator checks drive the real screen.
 */
fun installDebugInput(activity: ComponentActivity, graph: AppGraph) {
    val choice = InputSeam.choose(activity) ?: return
    if (activity.intent?.hasExtra(InputSeam.EXTRA_A4_HZ) == true) graph.prefs.update { it.copy(a4Hz = choice.a4Hz) }
    if (!choice.needsMicrophone) graph.tune.micAllowed = { true }
    graph.tune.makeInput = choice::make
    activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val readout = graph.tune.state.value.readout
                val diagnostics = graph.tune.diagnostics.value
                Log.i("ticktock.debug", "readout note=${readout.note ?: "—"} cents=${readout.cents ?: "—"} hz=${readout.hz ?: 0.0} hops=${diagnostics.counters.hopsAnalysed} dropped=${diagnostics.counters.droppedHops} hopNanos=${diagnostics.lastHopNanos}")
                delay(1_000)
            }
        }
    }
}
