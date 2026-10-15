// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.catalogue

/** Picker grouping, in display order. */
enum class Family(val displayName: String) {
    SAXOPHONE("Saxophones"),
    CLARINET("Clarinets"),
    FLUTE("Flutes"),
    TRUMPET("Trumpets"),
    HORN("Horns"),
    TROMBONE("Trombones"),
    LOW_BRASS("Low brass"),
    DOUBLE_REED("Double reeds"),
    RECORDER("Recorders"),
    BOWED_STRING("Bowed strings"),
    PLUCKED_STRING("Plucked strings"),
    VOICE_AND_KEYBOARD("Voice and keyboard"),
}

/**
 * One catalogue entry. [transposition] is sounding minus written in semitones
 * (TRN-05); a negative value sounds below the written note. [musescoreId] names
 * the base-layer entry the ranges come from; it is null for the catch-all key,
 * whose range is the whole detector window (RNG-10).
 */
data class Instrument(
    val key: String,
    val displayName: String,
    val family: Family,
    val transposition: Int,
    val musescoreId: String?,
) {
    /** True when every note the detector reads counts as in range. */
    val catchAll: Boolean get() = musescoreId == null
}
