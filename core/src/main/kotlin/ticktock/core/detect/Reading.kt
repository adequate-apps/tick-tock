// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

/**
 * One confirmed per-hop measurement. The display, the per-note histogram and
 * the saved history all consume this same stream (POL section 2). It carries
 * the A4 in force at its hop so a later A4 change can never file it under the
 * wrong run (POL-19).
 */
data class Reading(
    /** Index of the hop this reading came from, counted from stream start. */
    val hop: Long,
    val midiSounding: Int,
    val hz: Double,
    /** Signed cents from the nearest note at [a4Hz]. */
    val cents: Double,
    val a4Hz: Double,
    /** Increases on every A4 change, including a change to the same value. */
    val a4Generation: Int,
)
