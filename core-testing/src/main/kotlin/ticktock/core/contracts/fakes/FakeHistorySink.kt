// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts.fakes

import ticktock.core.contracts.HistorySink
import ticktock.core.detect.Reading

/**
 * A [HistorySink] that keeps accepted readings in a list and applies the SES-05
 * validation rule, so detector tests can assert what would have reached storage.
 */
class FakeHistorySink : HistorySink {
    val accepted = mutableListOf<Reading>()
    override var rejected: Int = 0
        private set

    override fun accept(reading: Reading) {
        if (isValid(reading)) accepted += reading else rejected++
    }

    companion object {
        /** The storage validation rule, stated once (SES-05). */
        fun isValid(r: Reading): Boolean =
            r.hz.isFinite() && r.cents.isFinite() && r.a4Hz.isFinite() &&
                r.hz in 1.0..20_000.0 &&
                r.midiSounding in 0..127 &&
                kotlin.math.abs(r.cents) <= 50.0
    }
}
