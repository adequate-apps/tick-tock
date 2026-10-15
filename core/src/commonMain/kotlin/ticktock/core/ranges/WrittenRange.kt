// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.ranges

/** An inclusive span of written MIDI notes. */
data class WrittenRange(val low: Int, val high: Int) {
    init {
        require(low <= high) { "written range runs backwards: $low..$high" }
    }

    operator fun contains(writtenMidi: Int): Boolean = writtenMidi in low..high

    /** The smallest range covering both this and [other]. */
    fun span(other: WrittenRange): WrittenRange = WrittenRange(minOf(low, other.low), maxOf(high, other.high))

    fun contains(other: WrittenRange): Boolean = other.low >= low && other.high <= high
}

/** Which layer decided a range end (owner decision 3: the UI shows this). */
enum class RangeLayer {
    BASE,
    APP_OVERLAY,
    USER,
}

data class RangeEnd(val midi: Int, val setBy: RangeLayer)

/** The resolved two-tier range for one key, with the layer that set each end. */
data class EffectiveRange(
    val keyedLow: RangeEnd,
    val keyedHigh: RangeEnd,
    val extendedLow: RangeEnd,
    val extendedHigh: RangeEnd,
) {
    val keyed: WrittenRange get() = WrittenRange(keyedLow.midi, keyedHigh.midi)
    val extended: WrittenRange get() = WrittenRange(extendedLow.midi, extendedHigh.midi)
}
