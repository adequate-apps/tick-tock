// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ticktock.core.contracts.OutputEvent

data class VisualBeat(val index: Long, val beat: Int, val bar: Long, val silentBar: Boolean, val audible: Boolean)

/** Native timestamps already name presentation time; only the player's route offset is added. */
class BeatVisuals(private val scope: CoroutineScope, private val nanoTime: () -> Long) {
    private val value = MutableStateFlow<VisualBeat?>(null)
    val state: StateFlow<VisualBeat?> = value
    private var generation = 0
    private var offsetMs = 0
    private var newest = -1L
    private var epoch = 0
    private val pending = mutableListOf<Job>()

    fun reset(generation: Int, offsetMs: Int) {
        pending.forEach(Job::cancel)
        pending.clear()
        this.generation = generation
        this.offsetMs = offsetMs
        newest = -1
        epoch++
        value.value = null
    }

    fun accept(beat: OutputEvent.Beat) {
        if (beat.generation != generation || beat.index <= newest) return
        newest = beat.index
        val currentEpoch = epoch
        val due = beat.nanos + offsetMs * NANOS_PER_MILLI
        pending.removeAll { it.isCompleted }
        pending += scope.launch {
            val remaining = due - nanoTime()
            if (remaining > 0) delay((remaining + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI)
            if (currentEpoch == epoch && (value.value?.index ?: -1) < beat.index) {
                value.value = VisualBeat(beat.index, beat.beatInBar, beat.bar, beat.silentBar, beat.audible)
            }
        }
    }

    private companion object { const val NANOS_PER_MILLI = 1_000_000L }
}
