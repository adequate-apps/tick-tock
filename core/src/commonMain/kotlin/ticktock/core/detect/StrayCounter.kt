// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import ticktock.core.catalogue.Instrument
import ticktock.core.ranges.BaseRanges
import ticktock.core.ranges.EffectiveRange
import ticktock.core.ranges.OverlayRanges
import ticktock.core.ranges.RangeResolver
import ticktock.core.ranges.UserRangeOverride

/**
 * Decides when to suggest that the selected instrument is wrong (spec 5). A
 * stray is a saved reading outside the instrument's extended range. The hint
 * shows once three different stray notes are alive at the same time; a stray
 * stays alive for [forgetAfterReadings] readings of any kind, so the window is
 * sounding time and rests age nothing (HNT-02). One long note, repeated
 * attacks on one note, a squeak and altissimo inside the extended range are
 * all one note or none, and never reach three (HNT-03).
 *
 * The counter observes the reading stream and changes nothing in it: cents
 * and Hz keep showing while the hint is up (HNT-08).
 */
class StrayCounter(
    instrument: Instrument,
    range: EffectiveRange?,
    private val forgetAfterReadings: Int = FORGET_AFTER_READINGS,
    private val distinctNotesForHint: Int = DISTINCT_NOTES_FOR_HINT,
) {
    var instrument: Instrument = instrument
        private set

    /** Null when the instrument has no range to judge against; the hint then never shows (HNT-07). */
    var range: EffectiveRange? = range
        private set

    private var readings = 0L

    /** Stray sounding notes, each with the reading count when it was last heard. */
    private val lastHeard = LinkedHashMap<Int, Long>()

    val hinting: Boolean get() = lastHeard.size >= distinctNotesForHint

    /** The stray notes currently alive, for the hint's text. */
    val strays: Set<Int> get() = lastHeard.keys.toSet()

    /** Counts one saved reading and returns whether the hint shows after it. */
    fun observe(reading: Reading): Boolean {
        readings++
        val range = range
        if (range != null && !instrument.catchAll && !RangeResolver.inExtendedRange(instrument, range, reading.midiSounding)) {
            lastHeard[reading.midiSounding] = readings
        }
        lastHeard.entries.removeIf { readings - it.value >= forgetAfterReadings }
        return hinting
    }

    /** An instrument change forgets every stray (HNT-07). */
    fun instrumentChanged(instrument: Instrument, range: EffectiveRange?) {
        this.instrument = instrument
        this.range = range
        lastHeard.clear()
    }

    companion object {
        /** About 20 s of sounding time at one reading per hop. */
        const val FORGET_AFTER_READINGS = 435

        const val DISTINCT_NOTES_FOR_HINT = 3

        /** A counter over the bundled range layers plus the player's override. */
        fun forInstrument(instrument: Instrument, user: UserRangeOverride? = null): StrayCounter {
            val range = if (instrument.catchAll) null else RangeResolver.resolve(instrument, BaseRanges.bundled, OverlayRanges.bundled, user)
            return StrayCounter(instrument, range)
        }
    }
}
