// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.prefs

import ticktock.core.config.DisplayMode
import ticktock.core.detect.ResponseMode
import ticktock.core.config.Spelling
import ticktock.core.config.wire
import ticktock.core.contracts.Voice
import ticktock.core.json.Json
import ticktock.core.json.JsonParseException
import ticktock.core.json.JsonValue
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound
import ticktock.core.metro.MetroSetup
import ticktock.core.music.Pitch

/** The result of reading a prefs blob. */
data class NormalisedPrefs(
    val prefs: Prefs,
    /** One sentence per corrected field, for Diagnostics; empty when the blob was clean. */
    val notes: List<String>,
    /** The schema version the blob declared, or null for a blob without one. */
    val storedVersion: Int?,
)

sealed class ReadResult {
    data class Ok(val result: NormalisedPrefs) : ReadResult()

    /** The text is not a prefs blob at all. The store copies it aside before writing (ERR-04). */
    data class Corrupt(val reason: String) : ReadResult()
}

/**
 * The single field table (SES-11). Every setting in the 2.0 scope is one row
 * here with its key, default, allowed values and portable flag; the
 * normaliser, the writer and the personal-config bridge are derived from the
 * rows, so adding a setting is adding a row and a property on [Prefs].
 *
 * The file is one flat JSON object: `schemaVersion` first, then the fields in
 * table order, then any field this version does not know, written back as it
 * was read (SES-12). Reading is lenient: a missing field is its default, a
 * wrong one is its default plus a note (POL-04 for the response mode), and
 * only text that is not a JSON object is refused as corrupt. A blob written
 * by a newer app is read for the fields this version knows and noted; the
 * next write carries this version, and the newer app reads it as it would
 * any older blob. [migrations] holds one step per past version; there are
 * none yet because version 1 is the first.
 */
object PrefsSchema {
    const val VERSION = 1
    const val VERSION_KEY = "schemaVersion"

    /** Longest free text the file keeps (nickname, equipment). */
    const val TEXT_MAX = 80

    /** Decision 16: the A4 reference range, defined once in [Pitch]. */
    val A4_HZ: ClosedFloatingPointRange<Double> = Pitch.A4_HZ

    /**
     * The "ignore sounds quieter than" setting, in dBFS on the UNPROCESSED
     * scale. The processed presets shift the gate 18 dB up (SRC-03), so the
     * top is where a full-scale sine still clears the gate with 6 dB to spare
     * under every preset: -30 dB becomes -12 dBFS under MIC, against a
     * full-scale sine's -3 dBFS.
     */
    val NOISE_FLOOR_DB: IntRange = -80..-30
    val VISUAL_OFFSET_MS: IntRange = -500..500
    val BEAT_UNITS: List<Int> = listOf(2, 4, 8, 16)

    val fields: List<PrefField<*>> = listOf(
        InstrumentField(
            "instrument",
            get = { it.instrumentKey },
            set = { p, v -> p.copy(instrumentKey = v) },
            portable = PortableLink({ it.instrumentKey }, { s, v -> s.copy(instrumentKey = v) }),
        ),
        DoubleField(
            "a4Hz", 440.0, A4_HZ,
            get = { it.a4Hz },
            set = { p, v -> p.copy(a4Hz = v) },
            portable = PortableLink({ it.a4Hz }, { s, v -> s.copy(a4Hz = v) }),
        ),
        ChoiceField(
            "displayMode", DisplayMode.WRITTEN, DisplayMode.entries, { it.wire },
            get = { it.displayMode },
            set = { p, v -> p.copy(displayMode = v) },
            portable = PortableLink({ it.displayMode }, { s, v -> s.copy(displayMode = v) }),
        ),
        ChoiceField(
            "spelling", Spelling.AUTO, Spelling.entries, { it.wire },
            get = { it.spelling },
            set = { p, v -> p.copy(spelling = v) },
            portable = PortableLink({ it.spelling }, { s, v -> s.copy(spelling = v) }),
        ),
        ChoiceField(
            "responseMode", ResponseMode.NORMAL, ResponseMode.entries, { it.wire },
            get = { it.responseMode },
            set = { p, v -> p.copy(responseMode = v) },
            portable = PortableLink({ it.responseMode }, { s, v -> s.copy(responseMode = v) }),
        ),
        IntField(
            "noiseFloorDb", -45, NOISE_FLOOR_DB,
            get = { it.noiseFloorDb },
            set = { p, v -> p.copy(noiseFloorDb = v) },
            portable = PortableLink({ it.noiseFloorDb }, { s, v -> s.copy(noiseFloorDb = v) }),
        ),
        BoolField(
            "keepAwake", true,
            get = { it.keepAwake },
            set = { p, v -> p.copy(keepAwake = v) },
            portable = PortableLink({ it.keepAwake }, { s, v -> s.copy(keepAwake = v) }),
        ),
        // CAP-02: device state that survives process death, never carried to another phone.
        BoolField("capturePaused", false, get = { it.capturePaused }, set = { p, v -> p.copy(capturePaused = v) }),
        BoolField(
            "earsFirst", false,
            get = { it.earsFirst },
            set = { p, v -> p.copy(earsFirst = v) },
            portable = PortableLink({ it.earsFirst }, { s, v -> s.copy(earsFirst = v) }),
        ),
        BoolField(
            "announceSettledNotes", false,
            get = { it.announceSettledNotes },
            set = { p, v -> p.copy(announceSettledNotes = v) },
            portable = PortableLink({ it.announceSettledNotes }, { s, v -> s.copy(announceSettledNotes = v) }),
        ),
        BoolField("includeDroneRuns", false, get = { it.includeDroneRuns }, set = { p, v -> p.copy(includeDroneRuns = v) }),
        BoolField("firstRunComplete", false, get = { it.firstRunComplete }, set = { p, v -> p.copy(firstRunComplete = v) }),
        TextField("hornNickname", "", TEXT_MAX, get = { it.hornNickname }, set = { p, v -> p.copy(hornNickname = v) }),
        TextField("lastEquipment", "", TEXT_MAX, get = { it.lastEquipment }, set = { p, v -> p.copy(lastEquipment = v) }),
        ChoiceField(
            "droneVoice", Voice.WARM, Voice.entries, { it.wire },
            get = { it.droneVoice },
            set = { p, v -> p.copy(droneVoice = v) },
            portable = PortableLink({ it.droneVoice }, { s, v -> s.copy(droneVoice = v) }),
        ),
        GainField("droneGain", 0.5f, get = { it.droneGain }, set = { p, v -> p.copy(droneGain = v) }),
        OptionalIntField("droneMidi", Pitch.DETECTOR_WINDOW, get = { it.droneMidi }, set = { p, v -> p.copy(droneMidi = v) }),
        ChoiceField(
            "clickSound", ClickSound.WOOD, ClickSound.entries, { it.wire },
            get = { it.clickSound },
            set = { p, v -> p.copy(clickSound = v) },
            portable = PortableLink({ it.clickSound }, { s, v -> s.copy(clickSound = v) }),
        ),
        GainField("clickGain", 0.8f, get = { it.clickGain }, set = { p, v -> p.copy(clickGain = v) }),
        IntField(
            "visualOffsetMs", 0, VISUAL_OFFSET_MS,
            get = { it.visualOffsetMs },
            set = { p, v -> p.copy(visualOffsetMs = v) },
            portable = PortableLink({ it.visualOffsetMs }, { s, v -> s.copy(visualOffsetMs = v) }),
        ),
        // BTC-01: measured on this phone's Bluetooth route, so it stays here.
        OptionalIntField("bluetoothVisualOffsetMs", VISUAL_OFFSET_MS, get = { it.bluetoothVisualOffsetMs }, set = { p, v -> p.copy(bluetoothVisualOffsetMs = v) }),
        IntField("metroBpm", 100, MetroSetup.BPM_MIN..MetroSetup.BPM_MAX, get = { it.metroBpm }, set = { p, v -> p.copy(metroBpm = v) }),
        IntField("metroBeatsPerBar", 4, 1..MetroSetup.BEATS_MAX, get = { it.metroBeatsPerBar }, set = { p, v -> p.copy(metroBeatsPerBar = v) }),
        IntChoiceField("metroBeatUnit", 4, BEAT_UNITS, get = { it.metroBeatUnit }, set = { p, v -> p.copy(metroBeatUnit = v) }),
        BoolField("metroCompoundPulse", false, get = { it.metroCompoundPulse }, set = { p, v -> p.copy(metroCompoundPulse = v) }),
        AccentsField("metroAccents", get = { it.metroAccents }, set = { p, v -> p.copy(metroAccents = v) }),
        IntField("metroSubdivision", 1, 1..MetroSetup.SUBDIVISION_MAX, get = { it.metroSubdivision }, set = { p, v -> p.copy(metroSubdivision = v) }),
        IntField("metroBarsOn", 1, 1..MetroSetup.GAP_BARS_MAX, get = { it.metroBarsOn }, set = { p, v -> p.copy(metroBarsOn = v) }),
        IntField("metroBarsOff", 0, 0..MetroSetup.GAP_BARS_MAX, get = { it.metroBarsOff }, set = { p, v -> p.copy(metroBarsOff = v) }),
    )

    private val byKey: Map<String, PrefField<*>> = fields.associateBy { it.key }

    fun field(key: String): PrefField<*>? = byKey[key]

    /** Steps that rewrite a blob from one past version to the next, indexed by the version they start from. */
    val migrations: Map<Int, (JsonValue.JsonObject) -> JsonValue.JsonObject> = emptyMap()

    fun read(text: String): ReadResult {
        val root = try {
            Json.parse(text)
        } catch (e: JsonParseException) {
            return ReadResult.Corrupt("the prefs file is not valid JSON (${e.message})")
        }
        val obj = root.obj ?: return ReadResult.Corrupt("the prefs file is not a JSON object")
        return ReadResult.Ok(normalise(obj))
    }

    fun normalise(stored: JsonValue.JsonObject): NormalisedPrefs {
        val notes = ArrayList<String>()
        val storedVersion = stored[VERSION_KEY]?.int
        var obj = stored
        if (storedVersion != null) {
            if (storedVersion > VERSION) {
                notes.add("$VERSION_KEY: $storedVersion is newer than this app writes ($VERSION); fields it does not know are kept as read")
            } else {
                for (from in storedVersion until VERSION) obj = migrations[from]?.invoke(obj) ?: obj
            }
        }

        var prefs = Prefs()
        for (field in fields) prefs = readField(field, prefs, obj[field.key], notes)
        prefs = prefs.copy(metroAccents = fitAccents(obj[ACCENTS_KEY], prefs, notes))

        val unknown = LinkedHashMap<String, JsonValue>()
        for (key in obj.keys) if (key != VERSION_KEY && key !in byKey) unknown[key] = obj[key]!!
        return NormalisedPrefs(prefs.copy(unknownFields = unknown), notes, storedVersion)
    }

    private fun <T> readField(field: PrefField<T>, prefs: Prefs, raw: JsonValue?, notes: MutableList<String>): Prefs {
        val read = field.normalise(raw)
        read.note?.let(notes::add)
        return field.set(prefs, read.value)
    }

    /**
     * The one rule between two fields: the accent row has an entry per beat.
     * An absent row takes the default shape for the beat count without a word;
     * a stored row of the wrong length is padded with plain beats or cut, and
     * noted, so a setup edited on a newer app still starts.
     */
    private fun fitAccents(raw: JsonValue?, prefs: Prefs, notes: MutableList<String>): List<Accent> {
        val beats = prefs.metroBeatsPerBar
        if (raw == null || raw is JsonValue.JsonNull) return Prefs.defaultAccentRow(beats)
        val row = prefs.metroAccents
        if (row.size == beats) return row
        notes.add("$ACCENTS_KEY: ${row.size} entries for $beats beats; ${if (row.size < beats) "padded with normal" else "cut to the beat count"}")
        return if (row.size < beats) row + List(beats - row.size) { Accent.NORMAL } else row.take(beats)
    }

    private const val ACCENTS_KEY = "metroAccents"

    fun toJson(prefs: Prefs): JsonValue.JsonObject {
        val root = Json.obj(VERSION_KEY to Json.num(VERSION))
        for (field in fields) root[field.key] = encodeField(field, prefs)
        prefs.unknownFields.forEach { (key, value) -> root[key] = value }
        return root
    }

    private fun <T> encodeField(field: PrefField<T>, prefs: Prefs): JsonValue = field.encode(field.get(prefs))

    fun write(prefs: Prefs): String = Json.write(toJson(prefs))
}
