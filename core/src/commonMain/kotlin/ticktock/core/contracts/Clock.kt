// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

/**
 * Time as the audio path sees it. Every timing rule counts frames (POL-09);
 * nanoseconds exist only to place visuals. Tests supply a fake that advances
 * on demand.
 */
interface Clock {
    /** Frames delivered or rendered so far on the stream this clock follows. */
    fun nowFrames(): Long

    /** Monotonic wall time, same base as the platform's elapsed-realtime clock. */
    fun nowNanos(): Long
}
