// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

/**
 * MIDI pitch facts shared by every module. The detector window is declared here
 * once (spec 0.3) so a range, a histogram and a test can never disagree about
 * the lowest and highest note the app reads.
 */
object Pitch {
    /** Sounding MIDI the detector reads: A0 to C8 inclusive. */
    val DETECTOR_WINDOW: IntRange = 21..108

    const val MIDI_A4 = 69

    /**
     * The A4 reference the player may set (decision 16): 415 covers baroque
     * pitch, 466 is the top that keeps every A4 within a semitone of 440, so
     * note naming stays unambiguous. The prefs table, the personal
     * configuration and the stepper all read this one range.
     */
    val A4_HZ: ClosedFloatingPointRange<Double> = 415.0..466.0

    /** Spelling-neutral label such as "A#/Bb3" or "C4"; octave numbers follow C4 = 60. */
    fun label(midi: Int): String {
        val octave = midi / 12 - 1
        return NAMES[Math.floorMod(midi, 12)] + octave
    }

    private val NAMES = arrayOf(
        "C", "C#/Db", "D", "D#/Eb", "E", "F", "F#/Gb", "G", "G#/Ab", "A", "A#/Bb", "B",
    )
}

/**
 * Transposition is sounding minus written, in semitones, for every instrument
 * with no per-family exception (TRN-05). Written = sounding - t.
 */
object Transposition {
    fun writtenFromSounding(soundingMidi: Int, transposition: Int): Int = soundingMidi - transposition

    fun soundingFromWritten(writtenMidi: Int, transposition: Int): Int = writtenMidi + transposition

    /** Picker text, for example "sounds a major 9th lower" or "sounds as written". */
    fun describe(transposition: Int): String {
        if (transposition == 0) return "sounds as written"
        val direction = if (transposition < 0) "lower" else "higher"
        val size = kotlin.math.abs(transposition)
        val octaves = size / 12
        val rest = size % 12
        val interval = when {
            rest == 0 && octaves == 1 -> "an octave"
            rest == 0 -> "$octaves octaves"
            octaves == 0 -> INTERVALS[rest]
            octaves == 1 -> "an octave and " + INTERVALS[rest].removePrefix("a ").removePrefix("an ")
            else -> "$octaves octaves and " + INTERVALS[rest].removePrefix("a ").removePrefix("an ")
        }
        return "sounds $interval $direction"
    }

    private val INTERVALS = arrayOf(
        "", "a minor 2nd", "a major 2nd", "a minor 3rd", "a major 3rd", "a perfect 4th",
        "a tritone", "a perfect 5th", "a minor 6th", "a major 6th", "a minor 7th", "a major 7th",
    )
}
