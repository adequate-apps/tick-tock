// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow

/**
 * MIDI pitch facts shared by every module. The detector window is declared here
 * once (spec 0.3) so a range, a histogram and a test can never disagree about
 * the lowest and highest note the app reads.
 */
object Pitch {
    /** Sounding MIDI the detector reads: A0 to C8 inclusive. */
    val DETECTOR_WINDOW: IntRange = 21..108

    const val MIDI_A4 = 69

    const val A4_DEFAULT_HZ = 440.0

    /**
     * The selectable concert-pitch reference (owner decision 16). 415 covers
     * baroque pitch; 466 is the top that keeps every A4 within a semitone of
     * 440, so a note name never flips between two references.
     */
    val A4_HZ: ClosedFloatingPointRange<Double> = 415.0..466.0

    /** Equal-tempered frequency of a (possibly fractional) MIDI number against [a4Hz]. */
    fun hzFromMidi(midi: Double, a4Hz: Double): Double = a4Hz * 2.0.pow((midi - MIDI_A4) / 12.0)

    fun hzFromMidi(midi: Int, a4Hz: Double): Double = hzFromMidi(midi.toDouble(), a4Hz)

    /** Fractional MIDI number of [hz] against [a4Hz]; NaN when [hz] is not a positive finite number. */
    fun midiFromHz(hz: Double, a4Hz: Double): Double =
        if (hz.isNaN() || hz <= 0.0 || hz.isInfinite()) Double.NaN else MIDI_A4 + 12.0 * log2(hz / a4Hz)

    /**
     * The nearest equal-tempered note to [hz] and the signed cents from it, or
     * null when [hz] carries no reading (BND-05). An exact quarter-tone rounds
     * to the upper note, so the cents are always in -50..50.
     */
    fun nearest(hz: Double, a4Hz: Double): NearestNote? {
        val midi = midiFromHz(hz, a4Hz)
        if (midi.isNaN()) return null
        val note = floor(midi + 0.5).toInt()
        return NearestNote(note, (midi - note) * 100.0)
    }

    /**
     * Signed cents of [hz] from a given [midi] at [a4Hz]. The table re-derives
     * stored readings through this when it shows them against a different A4
     * than they were graded with (POL-23). A semitone is 100 cents by
     * definition; the figure is a unit, never a threshold.
     */
    fun centsFrom(hz: Double, midi: Int, a4Hz: Double): Double = (midiFromHz(hz, a4Hz) - midi) * 100.0

    /** Spelling-neutral label such as "A#/Bb3" or "C4"; octave numbers follow C4 = 60. */
    fun label(midi: Int): String {
        val octave = midi / 12 - 1
        return NAMES[Math.floorMod(midi, 12)] + octave
    }

    private val NAMES = arrayOf(
        "C", "C#/Db", "D", "D#/Eb", "E", "F", "F#/Gb", "G", "G#/Ab", "A", "A#/Bb", "B",
    )
}

/** The note a frequency sits nearest to and how far off it is, in signed cents. */
data class NearestNote(val midi: Int, val cents: Double)

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
