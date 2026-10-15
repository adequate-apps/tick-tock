// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.catalogue

import ticktock.core.music.Pitch
import ticktock.core.music.Transposition
import ticktock.core.ranges.BaseRanges
import ticktock.core.ranges.EffectiveRange
import ticktock.core.ranges.OverlayRanges
import ticktock.core.ranges.RangeResolver
import ticktock.core.ranges.WrittenRange

/** The two notes ensembles tune to: bands on concert Bb, orchestras on concert A. */
enum class EnsembleNote(val concertPitchClass: Int, val label: String) {
    CONCERT_BB(10, "concert Bb"),
    CONCERT_A(9, "concert A"),
}

/** How a pad on the tone grid is marked (CHK-01). */
enum class PadMark { ENSEMBLE, CHECK }

/** One tuning note for one instrument, in both spellings, with the reason it is in the set. */
data class TuningNote(val writtenMidi: Int, val soundingMidi: Int, val reason: String)

/**
 * The tuning notes of one instrument: both ensemble notes in the octave that
 * suits the instrument, which of the two its ensemble uses, and the check set
 * the player steps through across registers (CHK-01 to CHK-03).
 */
data class TuningSet(
    val ensemble: Map<EnsembleNote, TuningNote>,
    val preferred: EnsembleNote,
    val check: List<TuningNote>,
) {
    /** The note the fixed drone starts on when the player has chosen none. */
    val defaultDrone: TuningNote get() = ensemble.getValue(preferred)

    /**
     * Marks for the pad sounding [soundingMidi]. An ensemble mark follows the
     * pitch class, so the grid shows it in every octave; a check mark is the
     * exact note.
     */
    fun marks(soundingMidi: Int): Set<PadMark> {
        val marks = LinkedHashSet<PadMark>()
        val pitchClass = soundingMidi.mod(12)
        if (EnsembleNote.entries.any { it.concertPitchClass == pitchClass }) marks.add(PadMark.ENSEMBLE)
        if (check.any { it.soundingMidi == soundingMidi }) marks.add(PadMark.CHECK)
        return marks
    }

    /**
     * The check note after the one sounding now (CHK-03). A drone on no note,
     * or on a note outside the set, starts the set from the top; the last
     * note wraps to the first.
     */
    fun nextCheck(currentSoundingMidi: Int?): TuningNote {
        val index = check.indexOfFirst { it.soundingMidi == currentSoundingMidi }
        return if (index < 0) check.first() else check[(index + 1) % check.size]
    }
}

/**
 * Tuning notes per catalogue key, from published tuning practice:
 *
 *  - Barnes, "A Practical Path to Better Tuning", The Instrumentalist, 2010:
 *    bands tune concert Bb, woodwinds then A; clarinets set open G on the
 *    barrel, low E on the bell and the twelfth; trumpets climb from written G
 *    to concert Bb; trombones and horns tune F; tubas low Bb.
 *  - The University of Oregon intonation worksheet: every saxophone tunes
 *    written F#, the baritone the bottom-space F#.
 *  - Jagow and the DANSR saxophone material: middle C# and fourth-line D are
 *    the classic tendency checks beside the F#.
 *  - Strings tune their open strings, the A string first.
 *
 * Families the sources describe by fingering (saxophones, clarinets) are
 * listed in written pitch, so every member shares the set. Sets the sources
 * describe in concert pitch (the trumpet climb, the upper woodwinds' notes,
 * horns, trombones, low brass, double reeds, strings) are listed sounding and
 * converted through each member's transposition, with the piccolo and bass
 * members of a family an octave up or down so the set sits in their own
 * register. Instruments without a published set (recorders, harp, voice,
 * piano, the catch-all) check their ensemble note in each register of their
 * keyed range. The two ensemble notes sit a semitone apart in one octave: the
 * octave of the check set's own concert Bb where it names one, else the
 * octave whose Bb is nearest the first check note, with both notes inside
 * the keyed range and the lower on a tie, so a tenor's Bb is written C5, a
 * tuba's is low Bb, and a horn's is the Bb its check set calls the band's.
 * The golden file pins all of it.
 */
object TuningNotes {
    fun forInstrument(instrument: Instrument): TuningSet =
        all[instrument.key] ?: throw NoSuchElementException("no tuning notes for \"${instrument.key}\"")

    // Built once for the whole catalogue: the sets are read from the analysis
    // and UI threads alike, so there is no per-call cache to race on.
    private val all: Map<String, TuningSet> by lazy { Catalogue.all.associate { it.key to build(it) } }

    private sealed class Spec {
        /** Fingering-based: the written notes every member of the family shares. */
        class Written(val notes: List<Pair<Int, String>>) : Spec()

        /** Concert-based: sounding notes, converted per instrument. */
        class Concert(val notes: List<Pair<Int, String>>) : Spec()

        /** The ensemble note in each register of the keyed range, nearest the fourth octave first. */
        class Registers(val note: EnsembleNote, val most: Int) : Spec()
    }

    private val saxophone = Spec.Written(listOf(78 to "top-line F#, the saxophone's tuning note", 73 to "middle C#, the open note", 74 to "fourth-line D, the register's sharp note"))
    private val baritoneSaxophone = Spec.Written(listOf(66 to "bottom-space F#, the baritone's tuning note", 73 to "middle C#, the open note", 74 to "fourth-line D, the register's sharp note"))
    private val clarinet = Spec.Written(listOf(67 to "open G, set on the barrel", 52 to "low E, set on the bell", 71 to "B, the twelfth above low E"))
    // Barnes: the upper woodwinds play concert F, G, A and Bb, and the trumpets
    // climb written G to C on the Bb trumpet, concert F to the band's Bb.
    private val flute = Spec.Concert(listOf(70 to "concert Bb, the band's note", 69 to "concert A, the orchestra's note", 65 to "concert F, the low register"))
    private val trumpet = Spec.Concert(listOf(65 to "concert F, where the climb starts", 67 to "concert G", 69 to "concert A", 70 to "concert Bb, the band's note, where the climb ends"))
    private val horn = Spec.Concert(listOf(65 to "concert F, the horn's note", 58 to "concert Bb, the band's note", 46 to "concert Bb, the low register"))
    // The mellophone's range starts above the horn's low Bb, so its third note is the upper Bb.
    private val mellophone = Spec.Concert(listOf(65 to "concert F, the horn's note", 58 to "concert Bb, the band's note", 70 to "concert Bb, the upper register"))
    private val lowVoice = Spec.Concert(listOf(46 to "concert Bb, the band's note", 53 to "concert F, the fifth above", 58 to "concert Bb, the upper register"))
    private val deepVoice = Spec.Concert(listOf(34 to "low Bb, the band's note two octaves down", 41 to "F, the fifth above", 46 to "Bb, the upper register"))
    private val oboe = Spec.Concert(listOf(69 to "concert A, the oboe's note", 70 to "concert Bb, the band's note", 65 to "concert F, the low register"))
    // The bassoon follows the oboe's A in the orchestra and plays the band's Bb too, an octave and a fifth down.
    private val bassoon = Spec.Concert(listOf(45 to "concert A, the orchestra's note", 46 to "concert Bb, the band's note", 53 to "concert F, the fifth above"))
    private val concertA = Spec.Registers(EnsembleNote.CONCERT_A, most = 3)

    private fun openStrings(vararg notes: Pair<Int, String>) = Spec.Concert(notes.toList())

    private fun Spec.Concert.octaves(shift: Int) = Spec.Concert(notes.map { (sounding, reason) -> sounding + 12 * shift to reason })

    private val byFamily: Map<Family, Spec> = mapOf(
        Family.SAXOPHONE to saxophone,
        Family.CLARINET to clarinet,
        Family.FLUTE to flute,
        Family.TRUMPET to trumpet,
        Family.HORN to horn,
        Family.TROMBONE to lowVoice,
        Family.LOW_BRASS to lowVoice,
        Family.DOUBLE_REED to oboe,
        Family.RECORDER to concertA,
        Family.BOWED_STRING to concertA,
        Family.PLUCKED_STRING to concertA,
        Family.VOICE_AND_KEYBOARD to concertA,
    )

    private val byKey: Map<String, Spec> = mapOf(
        "eb_bari" to baritoneSaxophone,
        "flute_piccolo" to flute.octaves(1),
        "flute_bass_c" to flute.octaves(-1),
        "trp_piccolo_bb" to trumpet.octaves(1),
        "trp_piccolo_a" to trumpet.octaves(1),
        "trp_bass_bb" to trumpet.octaves(-1),
        "mellophone_f" to mellophone,
        "tuba_f" to deepVoice,
        "tuba_eb" to deepVoice,
        "tuba_cc" to deepVoice,
        "tuba_bbb" to deepVoice,
        "sousaphone_bbb" to deepVoice,
        "bassoon" to bassoon,
        "contrabassoon" to bassoon.octaves(-1),
        "violin" to openStrings(69 to "open A string", 62 to "open D string", 55 to "open G string", 76 to "open E string"),
        "viola" to openStrings(69 to "open A string", 62 to "open D string", 55 to "open G string", 48 to "open C string"),
        "cello" to openStrings(57 to "open A string", 50 to "open D string", 43 to "open G string", 36 to "open C string"),
        "double_bass" to openStrings(33 to "open A string", 38 to "open D string", 43 to "open G string", 28 to "open E string"),
        "mandolin" to openStrings(69 to "open A course", 62 to "open D course", 55 to "open G course", 76 to "open E course"),
        "guitar" to openStrings(45 to "open A string", 50 to "open D string", 55 to "open G string", 64 to "open high E string"),
        "bass_guitar" to openStrings(33 to "open A string", 38 to "open D string", 43 to "open G string", 28 to "open E string"),
        "ukulele" to openStrings(69 to "open A string", 64 to "open E string", 60 to "open C string", 67 to "open G string"),
        "banjo" to openStrings(55 to "open G string", 59 to "open B string", 62 to "open D string", 50 to "open low D string"),
    )

    /** Bands tune Bb; the oboe gives the orchestra its A, and strings, recorders and keyboards follow it. */
    private val bandFamilies = setOf(Family.SAXOPHONE, Family.CLARINET, Family.FLUTE, Family.TRUMPET, Family.HORN, Family.TROMBONE, Family.LOW_BRASS)

    private fun build(instrument: Instrument): TuningSet {
        val range = RangeResolver.resolve(instrument, BaseRanges.bundled, OverlayRanges.bundled)
        val spec = byKey[instrument.key] ?: byFamily.getValue(instrument.family)
        val check = when (spec) {
            is Spec.Written -> spec.notes.map { (written, reason) -> written(instrument, written, reason) }
            is Spec.Concert -> spec.notes.map { (sounding, reason) -> sounding(instrument, sounding, reason) }
            is Spec.Registers -> registers(instrument, range, spec)
        }
        val preferred = if (instrument.family in bandFamilies) EnsembleNote.CONCERT_BB else EnsembleNote.CONCERT_A
        // The pads follow the check set: a set that names the band's Bb puts the pad on that very note.
        val bandBb = check.firstOrNull { it.soundingMidi.mod(12) == EnsembleNote.CONCERT_BB.concertPitchClass }
        val anchor = (bandBb ?: check.first()).soundingMidi
        val ensemble = EnsembleNote.entries.associateWith { note ->
            sounding(instrument, ensembleOctave(note, anchor, instrument, range), "${note.label}, the ensemble's note")
        }
        return TuningSet(ensemble, preferred, check)
    }

    private fun written(instrument: Instrument, writtenMidi: Int, reason: String) =
        TuningNote(writtenMidi, Transposition.soundingFromWritten(writtenMidi, instrument.transposition), reason)

    private fun sounding(instrument: Instrument, soundingMidi: Int, reason: String) =
        TuningNote(Transposition.writtenFromSounding(soundingMidi, instrument.transposition), soundingMidi, reason)

    private fun registers(instrument: Instrument, range: EffectiveRange, spec: Spec.Registers): List<TuningNote> {
        val candidates = octavesInside(spec.note.concertPitchClass, instrument, range.keyed)
            .sortedWith(compareBy({ kotlin.math.abs(it - Pitch.MIDI_A4) }, { it }))
            .take(spec.most)
        return candidates.map { sounding(instrument, it, "${spec.note.label} in this register") }
    }

    /** Every sounding MIDI of [pitchClass] whose written note lies inside [written]. */
    private fun octavesInside(pitchClass: Int, instrument: Instrument, written: WrittenRange): List<Int> =
        Pitch.DETECTOR_WINDOW.filter { sounding ->
            sounding.mod(12) == pitchClass &&
                Transposition.writtenFromSounding(sounding, instrument.transposition) in written
        }

    /**
     * The sounding MIDI of [note] for this instrument. Both ensemble notes are
     * placed in one octave, chosen by the Bb nearest [anchor] with the A below
     * it also inside the keyed range; an instrument whose keyed range holds no
     * such pair places each note on its own, nearest the anchor.
     */
    private fun ensembleOctave(note: EnsembleNote, anchor: Int, instrument: Instrument, range: EffectiveRange): Int {
        val pairedBb = octavesInside(EnsembleNote.CONCERT_BB.concertPitchClass, instrument, range.keyed)
            .filter { bb -> Transposition.writtenFromSounding(bb - 1, instrument.transposition) in range.keyed }
            .minWithOrNull(nearest(anchor))
        if (pairedBb != null) return pairedBb - (EnsembleNote.CONCERT_BB.concertPitchClass - note.concertPitchClass)
        val own = octavesInside(note.concertPitchClass, instrument, range.keyed).ifEmpty { octavesInside(note.concertPitchClass, instrument, range.extended) }
        return own.minWithOrNull(nearest(anchor))
            ?: throw IllegalStateException("${instrument.key}: no ${note.label} inside ${range.extended}")
    }

    private fun nearest(anchor: Int): Comparator<Int> = compareBy({ kotlin.math.abs(it - anchor) }, { it })
}
