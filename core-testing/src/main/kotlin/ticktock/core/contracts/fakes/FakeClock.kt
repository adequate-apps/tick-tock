// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts.fakes

import ticktock.core.contracts.Clock

/**
 * A [Clock] that moves only when a test says so. Frames and nanoseconds advance
 * together through [advanceFrames], so a test that reasons in hops never has
 * to convert by hand.
 */
class FakeClock(val sampleRate: Int = 48_000) : Clock {
    private var frames = 0L
    private var nanos = 0L

    override fun nowFrames(): Long = frames
    override fun nowNanos(): Long = nanos

    fun advanceFrames(count: Long) {
        require(count >= 0) { "a clock only moves forward" }
        frames += count
        nanos += count * NANOS_PER_SECOND / sampleRate
    }

    /** Advances wall time without delivering frames, the shape of a stalled stream. */
    fun advanceNanos(count: Long) {
        require(count >= 0) { "a clock only moves forward" }
        nanos += count
    }

    companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
