// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import ticktock.core.music.Pitch

/**
 * The concert-pitch reference in force, with a counter that grows on every
 * change, including a change to the same value (POL-21). A reading carries
 * both (POL-19), so a reading graded against one reference can never be filed
 * under a run that uses another (POL-20, POL-22).
 */
data class A4Generation(
    val hz: Double = Pitch.A4_DEFAULT_HZ,
    val generation: Int = 0,
) {
    init {
        require(hz in Pitch.A4_HZ) { "A4 must be within ${Pitch.A4_HZ.start}..${Pitch.A4_HZ.endInclusive} Hz, was $hz" }
        require(generation >= 0)
    }

    /** The next generation at [newHz]; the same value still counts as a change. */
    fun changedTo(newHz: Double): A4Generation = A4Generation(newHz, generation + 1)
}
