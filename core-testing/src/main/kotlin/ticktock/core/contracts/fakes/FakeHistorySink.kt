// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts.fakes

import ticktock.core.contracts.HistorySink
import ticktock.core.detect.Reading

/**
 * A [HistorySink] that keeps accepted readings in a list and applies the SES-05
 * validation rule, so detector tests can assert what would have reached storage.
 * [accept] holds the sink's monitor, so a test that reads the lists while the
 * analysis thread writes them synchronises on the sink.
 */
class FakeHistorySink : HistorySink {
    val accepted = mutableListOf<Reading>()

    /** The first-settled mark of each accepted reading, in the same order. */
    val firstSettled = mutableListOf<Boolean>()

    override var rejected: Int = 0
        private set

    @Synchronized
    override fun accept(reading: Reading, firstSettled: Boolean) {
        if (HistorySink.storable(reading)) {
            accepted += reading
            this.firstSettled += firstSettled
        } else {
            rejected++
        }
    }
}
