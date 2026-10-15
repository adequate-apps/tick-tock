// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.prefs

import ticktock.core.catalogue.Catalogue
import ticktock.core.config.DisplayMode
import ticktock.core.detect.ResponseMode
import ticktock.core.config.Spelling
import ticktock.core.contracts.Voice
import ticktock.core.json.JsonValue
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound
import ticktock.core.metro.MetroSetup

/**
 * Everything the app remembers between launches, as one value. The defaults
 * here are the ones a fresh install starts with; [PrefsSchema] is the table
 * that reads, checks and writes each property, and its test proves the two
 * never drift apart (SES-11). Every property is a plain value so a screen can
 * copy the parts it edits and hand the whole thing to the one writer.
 */
data class Prefs(
    // Tune
    val instrumentKey: String = Catalogue.DEFAULT_KEY,
    val a4Hz: Double = 440.0,
    val displayMode: DisplayMode = DisplayMode.WRITTEN,
    val spelling: Spelling = Spelling.AUTO,
    val responseMode: ResponseMode = ResponseMode.NORMAL,
    /** "Ignore sounds quieter than", in dBFS. */
    val noiseFloorDb: Int = -45,
    val keepAwake: Boolean = true,
    /** CAP-02: readings drive the needle and reach no store while this is set. */
    val capturePaused: Boolean = false,
    /** EAR-03: the readout hides while a note sounds and reveals after it. */
    val earsFirst: Boolean = false,
    /** TLK-02: a polite live region reads settled notes. Off by default. */
    val announceSettledNotes: Boolean = false,
    /** CTX-03: whether drone runs enter the All-history table. */
    val includeDroneRuns: Boolean = false,
    val firstRunComplete: Boolean = false,
    /** The horn label a run carries (SES-01, SES-02); empty means none. */
    val hornNickname: String = "",
    /** EQP-02: prefilled on the session sheet. */
    val lastEquipment: String = "",
    // Drone and reference tones
    val droneVoice: Voice = Voice.WARM,
    val droneGain: Float = 0.5f,
    /** Sounding MIDI of the fixed drone; null means the instrument's own tuning note. */
    val droneMidi: Int? = null,
    // Metronome: the current setup, kept across launches
    val clickSound: ClickSound = ClickSound.WOOD,
    val clickGain: Float = 0.8f,
    /** Visual offset for wired and speaker routes, in milliseconds; positive draws the beat later. */
    val visualOffsetMs: Int = 0,
    /** BTC-01: the tap-calibrated offset for Bluetooth routes; null until calibrated. */
    val bluetoothVisualOffsetMs: Int? = null,
    val metroBpm: Int = 100,
    val metroBeatsPerBar: Int = 4,
    val metroBeatUnit: Int = 4,
    val metroCompoundPulse: Boolean = false,
    /** One entry per beat; [PrefsSchema] keeps the length equal to [metroBeatsPerBar]. */
    val metroAccents: List<Accent> = defaultAccentRow(4),
    val metroSubdivision: Int = 1,
    val metroBarsOn: Int = 1,
    val metroBarsOff: Int = 0,
    /** The screen theme. Device-bound: a theme suits a room and a screen, so it never travels in a personal config. */
    val theme: Theme = Theme.GRIDLINE,
    /**
     * True once the app has asked for the microphone. Android tells a refusal
     * that can be asked again from one that cannot only after a first ask, so
     * the Tune pane needs this to offer the right key.
     */
    val micAsked: Boolean = false,
    /** Portable ranges, named horns and unread fields, saved together with the settings they accompany. */
    val personalConfig: ticktock.core.config.PersonalConfig = ticktock.core.config.PersonalConfig(),
    /** Fields this version does not know, written back unchanged (SES-12). */
    val unknownFields: Map<String, JsonValue> = emptyMap(),
) {
    /** The metronome's current setup as the engine takes it. */
    fun metroSetup(): MetroSetup = MetroSetup(
        bpm = metroBpm,
        beatsPerBar = metroBeatsPerBar,
        beatUnit = metroBeatUnit,
        compoundPulse = metroCompoundPulse,
        accents = metroAccents,
        subdivision = metroSubdivision,
        click = clickSound,
        gain = clickGain,
        barsOn = metroBarsOn,
        barsOff = metroBarsOff,
    )

    companion object {
        /** An accented downbeat and plain beats after it. */
        fun defaultAccentRow(beatsPerBar: Int): List<Accent> = List(beatsPerBar) { if (it == 0) Accent.ACCENT else Accent.NORMAL }
    }
}

/** The four screen themes (OWNER-FEEDBACK item 6); [wire] is the stored value. */
enum class Theme(val wire: String) { GRIDLINE("gridline"), DAYLIGHT("daylight"), DARK("dark"), PIT("pit") }
