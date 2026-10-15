// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import ticktock.core.catalogue.Family
import ticktock.core.catalogue.Instrument

/** How the five black keys are named. A natural note reads the same either way. */
enum class Accidentals { SHARPS, FLATS }

/**
 * Note names as the player reads them. The accidental choice follows the
 * instrument unless the player overrides it, and the chosen rule applies to
 * written and concert pitch alike.
 *
 * The automatic rule goes by family. Wind and brass players name notes in
 * flats: their instruments are pitched in Bb, Eb and F, their ensemble keys
 * are flat keys, and a saxophonist tunes to "Bb", never "A#". String,
 * plucked-string, voice and keyboard players name notes in sharps: open
 * strings and guitar keys sit in sharp keys. The catch-all concert-pitch entry
 * is a keyboard-family instrument, so it reads in sharps as the 1.x app did.
 */
object NoteSpelling {
    /** The accidentals an instrument's players use when nothing overrides them. */
    fun auto(instrument: Instrument): Accidentals = when (instrument.family) {
        Family.SAXOPHONE,
        Family.CLARINET,
        Family.FLUTE,
        Family.TRUMPET,
        Family.HORN,
        Family.TROMBONE,
        Family.LOW_BRASS,
        Family.DOUBLE_REED,
        Family.RECORDER,
        -> Accidentals.FLATS
        Family.BOWED_STRING,
        Family.PLUCKED_STRING,
        Family.VOICE_AND_KEYBOARD,
        -> Accidentals.SHARPS
    }

    /** The accidentals in force: the player's [override] when set, otherwise [auto]. */
    fun resolve(instrument: Instrument, override: Accidentals?): Accidentals = override ?: auto(instrument)

    /** Letter and accidental without the octave: "Bb", "A#", "C". */
    fun letter(midi: Int, accidentals: Accidentals): String {
        val names = if (accidentals == Accidentals.SHARPS) SHARP_NAMES else FLAT_NAMES
        return names[midi.mod(12)]
    }

    /** Full name with the scientific octave: "Bb4", "A#4", "C8". */
    fun name(midi: Int, accidentals: Accidentals): String = letter(midi, accidentals) + Pitch.octave(midi)

    private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT_NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
}
