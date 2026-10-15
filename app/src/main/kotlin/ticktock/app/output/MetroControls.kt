// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import ticktock.core.metro.Accent
import ticktock.core.metro.MetroSetup
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import kotlin.math.roundToInt

/** One tap installs the grouping as well as the signature (GAP-04). */
data class MetrePreset(val label: String, val unit: Int, val groups: List<Int>) {
    val beats: Int get() = groups.sum()
    val accents: List<Accent> get() {
        val starts = groups.runningFold(0, Int::plus).dropLast(1).toSet()
        return List(beats) { if (it in starts) Accent.ACCENT else Accent.NORMAL }
    }

    fun apply(prefs: Prefs): Prefs = prefs.copy(
        metroBeatsPerBar = beats, metroBeatUnit = unit,
        metroAccents = accents, metroCompoundPulse = false,
    )
}

object MetrePresets {
    val all = listOf(
        MetrePreset("3/4", 4, listOf(3)),
        MetrePreset("4/4", 4, listOf(4)),
        MetrePreset("6/8", 8, listOf(3, 3)),
        MetrePreset("5/4 (3+2)", 4, listOf(3, 2)),
        MetrePreset("7/8 (2+2+3)", 8, listOf(2, 2, 3)),
        MetrePreset("7/8 (3+2+2)", 8, listOf(3, 2, 2)),
        MetrePreset("9/8 (2+2+2+3)", 8, listOf(2, 2, 2, 3)),
    )

    fun custom(prefs: Prefs, beats: Int, unit: Int): Prefs {
        require(unit in PrefsSchema.BEAT_UNITS)
        val count = beats.coerceIn(1, MetroSetup.BEATS_MAX)
        return prefs.copy(
            metroBeatsPerBar = count, metroBeatUnit = unit,
            metroAccents = Prefs.defaultAccentRow(count), metroCompoundPulse = false,
        )
    }

    fun hasCompoundPulse(prefs: Prefs): Boolean =
        prefs.metroBeatUnit == 8 && prefs.metroBeatsPerBar in listOf(6, 9, 12)
}

/** Four intervals, with a fresh first tap after a two-second pause. */
class TapTempo {
    private var previous: Long? = null
    private val intervals = ArrayDeque<Long>()

    fun tap(nanos: Long): Int? {
        val elapsed = previous?.let { nanos - it }
        previous = nanos
        if (elapsed == null || elapsed <= 0 || elapsed >= RESET_NANOS) {
            intervals.clear()
            return null
        }
        intervals.addLast(elapsed)
        if (intervals.size > INTERVALS) intervals.removeFirst()
        return (MINUTE_NANOS / intervals.average()).roundToInt().coerceIn(MetroSetup.BPM_MIN, MetroSetup.BPM_MAX)
    }

    private companion object {
        const val INTERVALS = 4
        const val RESET_NANOS = 2_000_000_000L
        const val MINUTE_NANOS = 60_000_000_000.0
    }
}
