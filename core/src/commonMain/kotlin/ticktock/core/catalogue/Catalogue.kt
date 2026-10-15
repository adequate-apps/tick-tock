// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.catalogue

import ticktock.core.catalogue.Family.BOWED_STRING
import ticktock.core.catalogue.Family.CLARINET
import ticktock.core.catalogue.Family.DOUBLE_REED
import ticktock.core.catalogue.Family.FLUTE
import ticktock.core.catalogue.Family.HORN
import ticktock.core.catalogue.Family.LOW_BRASS
import ticktock.core.catalogue.Family.PLUCKED_STRING
import ticktock.core.catalogue.Family.RECORDER
import ticktock.core.catalogue.Family.SAXOPHONE
import ticktock.core.catalogue.Family.TROMBONE
import ticktock.core.catalogue.Family.TRUMPET
import ticktock.core.catalogue.Family.VOICE_AND_KEYBOARD

/**
 * The instrument catalogue, in picker order.
 *
 * Transpositions follow the value MuseScore declares for the mapped entry, which
 * `CatalogueTest` cross-checks against the base layer, with three recorded
 * decisions (TRN-01, TRN-04):
 *  - the sopranino and soprano recorders are +12: their parts are written an
 *    octave below sounding, and MuseScore expresses that through an octave clef
 *    with no chromatic transposition;
 *  - the BBb sousaphone is 0: the catalogue entry is the concert-pitch bass-clef
 *    part, where MuseScore's entry is the treble-clef brass-band part in Bb.
 * Every other octave clef (guitar, banjo, bass recorder, bass clarinet in
 * treble) is notation only and leaves the MIDI transposition untouched.
 */
object Catalogue {
    const val DEFAULT_KEY = "bb_tenor"

    /** The concert-pitch catch-all with no instrument to look up (RNG-10). */
    const val CATCH_ALL_KEY = "c"

    val all: List<Instrument> = listOf(
        Instrument("eb_sopranino", "Sopranino saxophone in Eb", SAXOPHONE, 3, "sopranino-saxophone"),
        Instrument("bb_soprano", "Soprano saxophone in Bb", SAXOPHONE, -2, "soprano-saxophone"),
        Instrument("eb_alto", "Alto saxophone in Eb", SAXOPHONE, -9, "alto-saxophone"),
        Instrument("bb_tenor", "Tenor saxophone in Bb", SAXOPHONE, -14, "tenor-saxophone"),
        Instrument("eb_bari", "Baritone saxophone in Eb", SAXOPHONE, -21, "baritone-saxophone"),
        Instrument("bb_bass", "Bass saxophone in Bb", SAXOPHONE, -26, "bass-saxophone"),
        Instrument("eb_contrabass", "Contrabass saxophone in Eb", SAXOPHONE, -33, "contrabass-saxophone"),

        Instrument("clar_eb", "Clarinet in Eb", CLARINET, 3, "eb-clarinet"),
        Instrument("clar_d", "Clarinet in D", CLARINET, 2, "d-clarinet"),
        Instrument("clar_c", "Clarinet in C", CLARINET, 0, "c-clarinet"),
        Instrument("clar_bb", "Clarinet in Bb", CLARINET, -2, "bb-clarinet"),
        Instrument("clar_a", "Clarinet in A", CLARINET, -3, "a-clarinet"),
        Instrument("clar_basset_f", "Basset horn in F", CLARINET, -7, "basset-horn"),
        Instrument("clar_alto_eb", "Alto clarinet in Eb", CLARINET, -9, "alto-clarinet"),
        Instrument("clar_bass_bb", "Bass clarinet in Bb", CLARINET, -14, "bb-bass-clarinet"),
        Instrument("clar_contraalto_eb", "Contra-alto clarinet in Eb", CLARINET, -21, "contra-alto-clarinet"),
        Instrument("clar_contrabass_bb", "Contrabass clarinet in Bb", CLARINET, -26, "contrabass-clarinet"),

        Instrument("flute_piccolo", "Piccolo", FLUTE, 12, "piccolo"),
        Instrument("flute_c", "Flute", FLUTE, 0, "flute"),
        Instrument("flute_alto_g", "Alto flute in G", FLUTE, -5, "alto-flute"),
        Instrument("flute_bass_c", "Bass flute", FLUTE, -12, "bass-flute"),

        Instrument("trp_piccolo_bb", "Piccolo trumpet in Bb", TRUMPET, 10, "bb-piccolo-trumpet"),
        Instrument("trp_piccolo_a", "Piccolo trumpet in A", TRUMPET, 9, "a-piccolo-trumpet"),
        Instrument("trp_f", "Trumpet in F", TRUMPET, 5, "f-trumpet"),
        Instrument("trp_e", "Trumpet in E", TRUMPET, 4, "e-trumpet"),
        Instrument("trp_eb", "Trumpet in Eb", TRUMPET, 3, "eb-trumpet"),
        Instrument("trp_d", "Trumpet in D", TRUMPET, 2, "d-trumpet"),
        Instrument("trp_c", "Trumpet in C", TRUMPET, 0, "c-trumpet"),
        Instrument("trp_bb", "Trumpet in Bb", TRUMPET, -2, "bb-trumpet"),
        Instrument("trp_a", "Trumpet in A", TRUMPET, -3, "a-trumpet"),
        Instrument("trp_bass_bb", "Bass trumpet in Bb", TRUMPET, -14, "bb-bass-trumpet"),
        Instrument("cornet_bb", "Cornet in Bb", TRUMPET, -2, "bb-cornet"),
        Instrument("flugel_bb", "Flugelhorn in Bb", TRUMPET, -2, "flugelhorn"),

        Instrument("horn_f", "Horn in F", HORN, -7, "horn"),
        Instrument("horn_bb", "Horn in Bb alto", HORN, -2, "bb-horn-alto"),
        Instrument("horn_eb_alto", "Alto horn in Eb", HORN, -9, "eb-alto-horn"),
        Instrument("mellophone_f", "Mellophone in F", HORN, -7, "mellophone"),

        Instrument("tbn_alto_eb", "Alto trombone", TROMBONE, 0, "alto-trombone"),
        Instrument("tbn_tenor", "Tenor trombone", TROMBONE, 0, "trombone"),
        Instrument("tbn_bass", "Bass trombone", TROMBONE, 0, "bass-trombone"),
        Instrument("tbn_contrabass", "Contrabass trombone", TROMBONE, 0, "contrabass-trombone"),

        Instrument("euph_bc", "Euphonium, bass clef", LOW_BRASS, 0, "euphonium"),
        Instrument("euph_tc", "Euphonium, treble clef in Bb", LOW_BRASS, -14, "euphonium-treble"),
        Instrument("baritone_bc", "Baritone horn, bass clef", LOW_BRASS, 0, "baritone-horn"),
        Instrument("baritone_tc", "Baritone horn, treble clef in Bb", LOW_BRASS, -14, "baritone-horn-treble"),
        Instrument("tuba_f", "Tuba in F", LOW_BRASS, 0, "f-tuba"),
        Instrument("tuba_eb", "Tuba in Eb", LOW_BRASS, 0, "eb-tuba"),
        Instrument("tuba_cc", "Tuba in CC", LOW_BRASS, 0, "c-tuba"),
        Instrument("tuba_bbb", "Tuba in BBb", LOW_BRASS, 0, "bb-tuba"),
        Instrument("sousaphone_bbb", "Sousaphone in BBb", LOW_BRASS, 0, "bb-sousaphone"),

        Instrument("oboe", "Oboe", DOUBLE_REED, 0, "oboe"),
        Instrument("oboe_damore", "Oboe d'amore in A", DOUBLE_REED, -3, "oboe-d'amore"),
        Instrument("english_horn", "English horn in F", DOUBLE_REED, -7, "english-horn"),
        Instrument("bassoon", "Bassoon", DOUBLE_REED, 0, "bassoon"),
        Instrument("contrabassoon", "Contrabassoon", DOUBLE_REED, -12, "contrabassoon"),

        Instrument("rec_sopranino_f", "Sopranino recorder in F", RECORDER, 12, "sopranino-recorder"),
        Instrument("rec_soprano_c", "Soprano recorder in C", RECORDER, 12, "soprano-recorder"),
        Instrument("rec_alto_f", "Alto recorder in F", RECORDER, 0, "alto-recorder"),
        Instrument("rec_tenor_c", "Tenor recorder in C", RECORDER, 0, "tenor-recorder"),
        Instrument("rec_bass_f", "Bass recorder in F", RECORDER, 0, "bass-recorder"),

        Instrument("violin", "Violin", BOWED_STRING, 0, "violin"),
        Instrument("viola", "Viola", BOWED_STRING, 0, "viola"),
        Instrument("cello", "Cello", BOWED_STRING, 0, "violoncello"),
        Instrument("double_bass", "Double bass", BOWED_STRING, -12, "contrabass"),
        Instrument("mandolin", "Mandolin", PLUCKED_STRING, 0, "mandolin"),

        Instrument("guitar", "Guitar", PLUCKED_STRING, 0, "guitar-steel"),
        Instrument("bass_guitar", "Bass guitar", PLUCKED_STRING, -12, "bass-guitar"),
        Instrument("ukulele", "Ukulele", PLUCKED_STRING, 0, "ukulele"),
        Instrument("banjo", "Banjo", PLUCKED_STRING, 0, "banjo"),
        Instrument("harp", "Harp", PLUCKED_STRING, 0, "harp"),

        Instrument("voice", "Voice", VOICE_AND_KEYBOARD, 0, "voice"),
        Instrument(CATCH_ALL_KEY, "Concert pitch (any instrument)", VOICE_AND_KEYBOARD, 0, null),
        Instrument("piano", "Piano", VOICE_AND_KEYBOARD, 0, "piano"),
    )

    private val byKey: Map<String, Instrument> = all.associateBy { it.key }

    fun find(key: String): Instrument? = byKey[key]

    fun get(key: String): Instrument = byKey[key] ?: throw NoSuchElementException("no instrument with key \"$key\"")

    val keys: Set<String> get() = byKey.keys

    /** Instruments grouped by family, families in enum order, instruments in catalogue order. */
    val byFamily: Map<Family, List<Instrument>> = Family.entries.associateWith { family -> all.filter { it.family == family } }
}
