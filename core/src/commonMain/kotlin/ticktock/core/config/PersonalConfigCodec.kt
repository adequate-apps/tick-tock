// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.config

import ticktock.core.contracts.Voice
import ticktock.core.detect.ResponseMode
import ticktock.core.metro.ClickSound
import ticktock.core.catalogue.Catalogue
import ticktock.core.json.Json
import ticktock.core.json.JsonParseException
import ticktock.core.json.JsonValue
import ticktock.core.music.Pitch
import ticktock.core.music.Transposition
import ticktock.core.ranges.UserRangeOverride
import ticktock.core.ranges.WrittenRange

/** One thing wrong with a file, located by a dotted path so the message can point at it. */
data class Problem(val path: String, val message: String) {
    override fun toString() = "$path: $message"
}

sealed class ParseResult {
    /** A usable configuration. [warnings] name fields that were preserved unread. */
    data class Ok(val config: PersonalConfig, val warnings: List<String>) : ParseResult()

    /** Nothing is applied. Every problem is listed so the player can fix the file once. */
    data class Failed(val problems: List<Problem>) : ParseResult()
}

/**
 * Parse, validate and write the portable personal configuration. Parsing is
 * strict about values and lenient about vocabulary: a wrong value anywhere
 * fails the whole file, while an unknown field is kept and reported. A newer
 * version is refused outright, because its values may mean something this
 * build cannot check.
 */
object PersonalConfigCodec {
    fun parse(text: String): ParseResult {
        val root = try {
            Json.parse(text)
        } catch (e: JsonParseException) {
            return ParseResult.Failed(listOf(Problem("file", "the file is not valid JSON (${e.message})")))
        }
        val obj = root.obj ?: return ParseResult.Failed(listOf(Problem("file", "the top level must be an object")))

        val format = obj["format"]?.string
        if (format != PersonalConfig.FORMAT) {
            return ParseResult.Failed(listOf(Problem("format", "expected \"${PersonalConfig.FORMAT}\", found ${describe(obj["format"])}")))
        }
        val version = obj["version"]?.int
            ?: return ParseResult.Failed(listOf(Problem("version", "expected an integer, found ${describe(obj["version"])}")))
        if (version > PersonalConfig.VERSION) {
            return ParseResult.Failed(listOf(Problem("version", "version $version is newer than this app reads (version ${PersonalConfig.VERSION}); update the app to import it")))
        }
        if (version < 1) return ParseResult.Failed(listOf(Problem("version", "version $version is not a valid version")))

        val problems = ArrayList<Problem>()
        val warnings = ArrayList<String>()
        val overrides = parseOverrides(obj["rangeOverrides"], problems)
        val profiles = parseProfiles(obj["hornProfiles"], problems)
        val settings = parseSettings(obj["settings"], problems, warnings)
        val unknown = LinkedHashMap<String, JsonValue>()
        for (key in obj.keys) {
            if (key !in KNOWN_TOP_LEVEL) {
                unknown[key] = obj[key]!!
                warnings.add("field \"$key\" is not known to this version and is kept as read")
            }
        }
        if (problems.isNotEmpty()) return ParseResult.Failed(problems)
        return ParseResult.Ok(PersonalConfig(overrides, profiles, settings, unknown), warnings)
    }

    fun write(config: PersonalConfig): String = Json.write(toJson(config))

    fun toJson(config: PersonalConfig): JsonValue.JsonObject {
        val root = Json.obj(
            "format" to Json.str(PersonalConfig.FORMAT),
            "version" to Json.num(PersonalConfig.VERSION),
        )
        root["rangeOverrides"] = Json.obj(
            *config.rangeOverrides.entries.map { (key, override) ->
                key to Json.obj("keyedLow" to Json.num(override.keyed.low), "keyedHigh" to Json.num(override.keyed.high))
            }.toTypedArray(),
        )
        root["hornProfiles"] = Json.arr(
            config.hornProfiles.map {
                Json.obj("id" to Json.str(it.id), "instrument" to Json.str(it.instrumentKey), "nickname" to Json.str(it.nickname))
            },
        )
        root["settings"] = settingsJson(config.settings)
        config.unknownFields.forEach { (key, value) -> root[key] = value }
        return root
    }

    private val KNOWN_TOP_LEVEL = setOf("format", "version", "rangeOverrides", "hornProfiles", "settings")

    private fun parseOverrides(value: JsonValue?, problems: MutableList<Problem>): Map<String, UserRangeOverride> {
        val result = LinkedHashMap<String, UserRangeOverride>()
        if (value == null || value is JsonValue.JsonNull) return result
        val fields = value.obj?.fields
        if (fields == null) {
            problems.add(Problem("rangeOverrides", "expected an object keyed by instrument"))
            return result
        }
        for ((key, raw) in fields) {
            val path = "rangeOverrides.$key"
            val instrument = Catalogue.find(key)
            if (instrument == null) {
                problems.add(Problem(path, "\"$key\" is not an instrument this app knows"))
                continue
            }
            val entry = raw.obj
            if (entry == null) {
                problems.add(Problem(path, "expected an object with keyedLow and keyedHigh"))
                continue
            }
            val low = entry["keyedLow"]?.int
            val high = entry["keyedHigh"]?.int
            if (low == null) problems.add(Problem("$path.keyedLow", "expected a whole MIDI number, found ${describe(entry["keyedLow"])}"))
            if (high == null) problems.add(Problem("$path.keyedHigh", "expected a whole MIDI number, found ${describe(entry["keyedHigh"])}"))
            if (low == null || high == null) continue
            if (low > high) {
                problems.add(Problem(path, "keyedLow ${Pitch.label(low)} is above keyedHigh ${Pitch.label(high)}"))
                continue
            }
            val window = Pitch.DETECTOR_WINDOW
            val soundingLow = Transposition.soundingFromWritten(low, instrument.transposition)
            val soundingHigh = Transposition.soundingFromWritten(high, instrument.transposition)
            if (soundingLow < window.first || soundingHigh > window.last) {
                problems.add(
                    Problem(
                        path,
                        "written ${Pitch.label(low)}..${Pitch.label(high)} sounds outside the notes the app reads " +
                            "(${Pitch.label(window.first)}..${Pitch.label(window.last)} sounding)",
                    ),
                )
                continue
            }
            result[key] = UserRangeOverride(WrittenRange(low, high))
        }
        return result
    }

    private fun parseProfiles(value: JsonValue?, problems: MutableList<Problem>): List<HornProfile> {
        val result = ArrayList<HornProfile>()
        if (value == null || value is JsonValue.JsonNull) return result
        val items = value.array?.items
        if (items == null) {
            problems.add(Problem("hornProfiles", "expected a list"))
            return result
        }
        val seen = HashSet<String>()
        items.forEachIndexed { index, raw ->
            val path = "hornProfiles[$index]"
            val entry = raw.obj
            if (entry == null) {
                problems.add(Problem(path, "expected an object with id, instrument and nickname"))
                return@forEachIndexed
            }
            val id = entry["id"]?.string?.takeIf { it.isNotBlank() }
            val instrument = entry["instrument"]?.string
            val nickname = entry["nickname"]?.string?.trim()
            if (id == null) problems.add(Problem("$path.id", "expected a non-empty text id"))
            if (instrument == null) {
                problems.add(Problem("$path.instrument", "expected an instrument key"))
            } else if (Catalogue.find(instrument) == null) {
                problems.add(Problem("$path.instrument", "\"$instrument\" is not an instrument this app knows"))
            }
            if (nickname.isNullOrEmpty()) problems.add(Problem("$path.nickname", "expected a non-empty nickname"))
            if (id != null && !seen.add(id)) problems.add(Problem("$path.id", "id \"$id\" appears more than once"))
            if (id != null && instrument != null && Catalogue.find(instrument) != null && !nickname.isNullOrEmpty()) {
                result.add(HornProfile(id, instrument, nickname))
            }
        }
        return result
    }

    private fun parseSettings(value: JsonValue?, problems: MutableList<Problem>, warnings: MutableList<String>): PortableSettings {
        if (value == null || value is JsonValue.JsonNull) return PortableSettings()
        val obj = value.obj
        if (obj == null) {
            problems.add(Problem("settings", "expected an object"))
            return PortableSettings()
        }
        var settings = PortableSettings()
        val unknown = LinkedHashMap<String, JsonValue>()
        for ((key, raw) in obj.fields) {
            val path = "settings.$key"
            when (key) {
                "instrument" -> {
                    val text = raw.string
                    if (text == null) problems.add(Problem(path, "expected an instrument key, found ${describe(raw)}"))
                    else if (Catalogue.find(text) == null) problems.add(Problem(path, "\"$text\" is not an instrument this app knows"))
                    else settings = settings.copy(instrumentKey = text)
                }
                "a4Hz" -> {
                    val hz = raw.number
                    if (hz == null || hz !in PortableSettings.A4_HZ) {
                        problems.add(Problem(path, "expected a number from ${PortableSettings.A4_HZ.start.toInt()} to ${PortableSettings.A4_HZ.endInclusive.toInt()}, found ${describe(raw)}"))
                    } else settings = settings.copy(a4Hz = hz)
                }
                "displayMode" -> enumValue(raw, path, DisplayMode.entries, { it.wire }, problems)?.let { settings = settings.copy(displayMode = it) }
                "spelling" -> enumValue(raw, path, Spelling.entries, { it.wire }, problems)?.let { settings = settings.copy(spelling = it) }
                "responseMode" -> enumValue(raw, path, ResponseMode.entries, { it.wire }, problems)?.let { settings = settings.copy(responseMode = it) }
                "droneVoice" -> enumValue(raw, path, Voice.entries, { it.wire }, problems)?.let { settings = settings.copy(droneVoice = it) }
                "clickSound" -> enumValue(raw, path, ClickSound.entries, { it.wire }, problems)?.let { settings = settings.copy(clickSound = it) }
                "noiseFloorDb" -> intIn(raw, path, PortableSettings.NOISE_FLOOR_DB, problems)?.let { settings = settings.copy(noiseFloorDb = it) }
                "visualOffsetMs" -> intIn(raw, path, PortableSettings.VISUAL_OFFSET_MS, problems)?.let { settings = settings.copy(visualOffsetMs = it) }
                "keepAwake" -> flag(raw, path, problems)?.let { settings = settings.copy(keepAwake = it) }
                "earsFirst" -> flag(raw, path, problems)?.let { settings = settings.copy(earsFirst = it) }
                "announceSettledNotes" -> flag(raw, path, problems)?.let { settings = settings.copy(announceSettledNotes = it) }
                else -> {
                    unknown[key] = raw
                    warnings.add("setting \"$key\" is not known to this version and is kept as read")
                }
            }
        }
        return settings.copy(unknownFields = unknown)
    }

    private fun <T> enumValue(raw: JsonValue, path: String, entries: List<T>, wire: (T) -> String, problems: MutableList<Problem>): T? {
        val text = raw.string
        val match = entries.firstOrNull { wire(it) == text }
        if (match == null) {
            problems.add(Problem(path, "expected one of ${entries.joinToString { "\"${wire(it)}\"" }}, found ${describe(raw)}"))
        }
        return match
    }

    private fun flag(raw: JsonValue, path: String, problems: MutableList<Problem>): Boolean? {
        val value = raw.bool
        if (value == null) problems.add(Problem(path, "expected true or false, found ${describe(raw)}"))
        return value
    }

    private fun intIn(raw: JsonValue, path: String, range: IntRange, problems: MutableList<Problem>): Int? {
        val value = raw.int
        if (value == null || value !in range) {
            problems.add(Problem(path, "expected a whole number from ${range.first} to ${range.last}, found ${describe(raw)}"))
            return null
        }
        return value
    }

    private fun settingsJson(settings: PortableSettings): JsonValue.JsonObject {
        val obj = Json.obj()
        settings.instrumentKey?.let { obj["instrument"] = Json.str(it) }
        settings.a4Hz?.let { obj["a4Hz"] = Json.num(it) }
        settings.displayMode?.let { obj["displayMode"] = Json.str(it.wire) }
        settings.spelling?.let { obj["spelling"] = Json.str(it.wire) }
        settings.responseMode?.let { obj["responseMode"] = Json.str(it.wire) }
        settings.noiseFloorDb?.let { obj["noiseFloorDb"] = Json.num(it) }
        settings.keepAwake?.let { obj["keepAwake"] = Json.bool(it) }
        settings.earsFirst?.let { obj["earsFirst"] = Json.bool(it) }
        settings.announceSettledNotes?.let { obj["announceSettledNotes"] = Json.bool(it) }
        settings.droneVoice?.let { obj["droneVoice"] = Json.str(it.wire) }
        settings.clickSound?.let { obj["clickSound"] = Json.str(it.wire) }
        settings.visualOffsetMs?.let { obj["visualOffsetMs"] = Json.num(it) }
        settings.unknownFields.forEach { (key, value) -> obj[key] = value }
        return obj
    }

    private fun describe(value: JsonValue?): String = when (value) {
        null -> "nothing"
        is JsonValue.JsonNull -> "null"
        is JsonValue.JsonString -> "\"${value.value}\""
        is JsonValue.JsonNumber -> Json.write(value).trim()
        is JsonValue.JsonBool -> value.value.toString()
        is JsonValue.JsonArray -> "a list"
        is JsonValue.JsonObject -> "an object"
    }
}
