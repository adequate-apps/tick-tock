// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

/** Every hop is exactly one of these (spec 2). */
enum class HopClass { REJECT, SKIP, VOTE }

enum class RejectReason {
    /** Below the mode's RMS floor after mic gain (POL-11). */
    SILENCE,
    /** The estimator found no period. */
    NO_CANDIDATE,
    /** Above the mode's aperiodicity ceiling (POL-10). */
    APERIODIC,
    /** Nearest note outside A0..C8 (POL-13). */
    OUTSIDE_WINDOW,
}

enum class SkipReason {
    /** The candidate is a note the output is sounding and not yet proven to be the player's (POL-25). */
    BLEED,
}

/**
 * A note won the vote (POL-06). [landingCents] is the mean cents over the
 * hops that confirmed it: where the attack landed before the tone settled,
 * emitted here so the attack is one event with one number for the table.
 */
data class Attack(
    val hop: Long,
    val midiSounding: Int,
    val landingCents: Double,
    val a4Generation: Int,
)

enum class ReleaseCause {
    /** Every vote for the note aged out of the window with silence behind it. */
    SILENCE,
    /** Another note won. */
    NOTE_CHANGE,
    /** Other notes pushed its votes out without any of them winning. */
    UNCONFIRMED_NOTES,
    /** The output started sounding the same note and the player is not yet proven to hold it. */
    BLEED,
}

/** A held note ended (POL-07); [discardedPending] readings never reached history. */
data class Release(
    val hop: Long,
    val midiSounding: Int,
    val discardedPending: Int,
    val cause: ReleaseCause,
)

/**
 * What the detector decided about one hop. The display reads [incumbent] and
 * [display]; the histogram and the history read [saved]; the engine receives
 * [duck]. One stream feeds all three, which is the point (spec 2).
 */
data class Decision(
    val hop: Long,
    val hopClass: HopClass,
    val reject: RejectReason? = null,
    val skip: SkipReason? = null,
    /** Hop RMS after mic gain, for the level meter (POL-12). */
    val level: Double,
    /** The note the readout shows after this hop; null is a blank readout. */
    val incumbent: Int?,
    /** A fresh reading of the incumbent from this hop, or null when the readout holds its last value. */
    val display: Reading? = null,
    /** A reading released to history and the statistics this hop. */
    val saved: Reading? = null,
    /** True when [saved] is the first settled reading after an [Attack] (ATK-02). */
    val firstSettled: Boolean = false,
    val attack: Attack? = null,
    val release: Release? = null,
    /** Output gain the bleed policy asks for this hop, if it changed (POL-26). */
    val duck: Float? = null,
)
