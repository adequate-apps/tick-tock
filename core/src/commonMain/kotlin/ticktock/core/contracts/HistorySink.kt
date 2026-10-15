// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

import ticktock.core.detect.Reading
import kotlin.math.abs

/**
 * Where confirmed readings go. The analysis thread calls [accept] once per
 * saved reading; the implementation buffers and flushes on its own schedule
 * (SES-06), so a call never waits on storage. A reading that fails
 * [storable] is counted and reported through [rejected], never dropped
 * silently (SES-05).
 */
interface HistorySink {
    /**
     * [firstSettled] marks the first settled reading after an attack, the one
     * whose cents count as that attack's landing in the table (ATK-02).
     */
    fun accept(reading: Reading, firstSettled: Boolean = false)

    /** Readings refused by validation since the sink was created. */
    val rejected: Int

    companion object {
        /** The storage validation rule, stated once (SES-05). */
        fun storable(r: Reading): Boolean =
            r.hz.isFinite() && r.cents.isFinite() && r.a4Hz.isFinite() &&
                r.hz in 1.0..20_000.0 &&
                r.midiSounding in 0..127 &&
                abs(r.cents) <= 50.0
    }
}
