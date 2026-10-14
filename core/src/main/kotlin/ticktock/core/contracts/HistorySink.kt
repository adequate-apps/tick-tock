// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

import ticktock.core.detect.Reading

/**
 * Where confirmed readings go. The detector calls [accept] once per saved
 * reading on the analysis thread; the implementation buffers and flushes on
 * its own schedule (SES-06). A reading that fails validation is counted and
 * reported through [rejected], never dropped silently (SES-05).
 */
interface HistorySink {
    fun accept(reading: Reading)

    /** Readings refused by validation since the sink was created. */
    val rejected: Int
}
