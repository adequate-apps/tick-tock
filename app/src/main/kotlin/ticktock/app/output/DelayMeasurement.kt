// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.output

import ticktock.core.contracts.OutputEvent
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound
import ticktock.core.metro.MetroSetup
import ticktock.core.prefs.PrefsSchema
import kotlin.math.roundToInt

/** One tap per native click. A repeated tap cannot replace the first observation. */
class DelayMeasurement(val generation: Int) {
    private val clicks = arrayOfNulls<Long>(CLICKS)
    private val taps = arrayOfNulls<Long>(CLICKS)
    var latest: Int = -1
        private set
    val clicksReceived: Int get() = clicks.count { it != null }
    val tapsReceived: Int get() = taps.count { it != null }
    val lastClickNanos: Long? get() = clicks.last()

    fun beat(event: OutputEvent.Beat): Boolean {
        if (event.generation != generation || event.index !in 0L until CLICKS || !event.audible) return false
        val index = event.index.toInt()
        if (clicks[index] != null) return false
        clicks[index] = event.nanos
        latest = maxOf(latest, index)
        return true
    }

    fun tap(nanos: Long, index: Int = latest): Boolean {
        if (index !in taps.indices || taps[index] != null) return false
        val click = clicks[index] ?: return false
        val offset = (nanos - click).toDouble() / NANOS_PER_MILLI
        if (offset < PrefsSchema.VISUAL_OFFSET_MS.first || offset > PrefsSchema.VISUAL_OFFSET_MS.last) return false
        taps[index] = nanos
        return true
    }

    fun medianMs(): Int? {
        if (tapsReceived != CLICKS || clicksReceived != CLICKS) return null
        val offsets = clicks.indices.map { (taps[it]!! - clicks[it]!!).toDouble() / NANOS_PER_MILLI }.sorted()
        return ((offsets[CLICKS / 2 - 1] + offsets[CLICKS / 2]) / 2).roundToInt()
    }

    fun setup() = MetroSetup(
        bpm = BPM, beatsPerBar = 4, beatUnit = 4, compoundPulse = false,
        accents = List(4) { Accent.NORMAL }, subdivision = 1, click = ClickSound.CLICK,
        gain = GAIN, barsOn = 1, barsOff = 0, beatLimit = CLICKS, generation = generation,
    )

    companion object {
        const val CLICKS = 8
        const val BPM = 100
        const val GAIN = 0.5f
        const val NANOS_PER_MILLI = 1_000_000L
        const val CLICK_TAIL_NANOS = 30_000_000L
        const val TIMEOUT_MILLIS = 8_000L
    }
}
