// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.config

import ticktock.core.catalogue.Catalogue
import ticktock.core.json.JsonValue
import ticktock.core.music.Pitch
import ticktock.core.ranges.WrittenRange

/** One line of "what this import will change", with the values before and after. */
data class ImportChange(val section: Section, val key: String, val before: String?, val after: String, val sentence: String) {
    enum class Section { RANGE_OVERRIDE, HORN_PROFILE, SETTING, NEWER_VERSION }
}

/** Everything an import would change, shown to the player before anything is applied. */
data class ImportDiff(val changes: List<ImportChange>) {
    val isEmpty: Boolean get() = changes.isEmpty()

    /** One sentence per change, or a single sentence when nothing changes. */
    fun sentences(): List<String> = if (isEmpty) listOf("Nothing will change.") else changes.map { it.sentence }
}

/**
 * Pure merge and diff of two configurations. The incoming file wins wherever
 * it says something; whatever it leaves out stays as it is on this device.
 * Nothing is deleted by an import.
 */
object PersonalConfigMerge {
    fun merge(current: PersonalConfig, incoming: PersonalConfig): PersonalConfig {
        val overrides = LinkedHashMap(current.rangeOverrides)
        overrides.putAll(incoming.rangeOverrides)

        val profiles = ArrayList<HornProfile>()
        val incomingById = incoming.hornProfiles.associateBy { it.id }
        for (profile in current.hornProfiles) profiles.add(incomingById[profile.id] ?: profile)
        val currentIds = current.hornProfiles.map { it.id }.toSet()
        for (profile in incoming.hornProfiles) if (profile.id !in currentIds) profiles.add(profile)

        val unknown = LinkedHashMap(current.unknownFields)
        unknown.putAll(incoming.unknownFields)

        return PersonalConfig(overrides, profiles, mergeSettings(current.settings, incoming.settings), unknown)
    }

    fun diff(current: PersonalConfig, incoming: PersonalConfig): ImportDiff {
        val changes = ArrayList<ImportChange>()

        for ((key, override) in incoming.rangeOverrides) {
            val before = current.rangeOverrides[key]
            if (before == override) continue
            val name = Catalogue.find(key)?.displayName ?: key
            val after = label(override.keyed)
            val sentence = if (before == null) {
                "Range for $name: $after replaces the default."
            } else {
                "Range for $name: ${label(before.keyed)} becomes $after."
            }
            changes.add(ImportChange(ImportChange.Section.RANGE_OVERRIDE, key, before?.let { label(it.keyed) }, after, sentence))
        }

        val currentProfiles = current.hornProfiles.associateBy { it.id }
        for (profile in incoming.hornProfiles) {
            val before = currentProfiles[profile.id]
            if (before == profile) continue
            val name = Catalogue.find(profile.instrumentKey)?.displayName ?: profile.instrumentKey
            val after = "\"${profile.nickname}\" ($name)"
            val sentence = if (before == null) {
                "Horn profile $after is added."
            } else {
                val beforeName = Catalogue.find(before.instrumentKey)?.displayName ?: before.instrumentKey
                "Horn profile \"${before.nickname}\" ($beforeName) becomes $after."
            }
            changes.add(ImportChange(ImportChange.Section.HORN_PROFILE, profile.id, before?.let { "\"${it.nickname}\"" }, after, sentence))
        }

        settingChange(changes, "instrument", current.settings.instrumentKey, incoming.settings.instrumentKey, "Instrument") { Catalogue.find(it)?.displayName ?: it }
        settingChange(changes, "a4Hz", current.settings.a4Hz, incoming.settings.a4Hz, "A4") { "${trimHz(it)} Hz" }
        settingChange(changes, "displayMode", current.settings.displayMode, incoming.settings.displayMode, "Note names") { it.wire }
        settingChange(changes, "spelling", current.settings.spelling, incoming.settings.spelling, "Spelling") { it.wire }
        settingChange(changes, "responseMode", current.settings.responseMode, incoming.settings.responseMode, "Response") { it.wire }
        settingChange(changes, "noiseFloorDb", current.settings.noiseFloorDb, incoming.settings.noiseFloorDb, "Ignore sounds quieter than") { "$it dB" }
        settingChange(changes, "keepAwake", current.settings.keepAwake, incoming.settings.keepAwake, "Keep screen awake") { if (it) "on" else "off" }
        settingChange(changes, "droneVoice", current.settings.droneVoice, incoming.settings.droneVoice, "Drone voice") { it.wire }
        settingChange(changes, "clickSound", current.settings.clickSound, incoming.settings.clickSound, "Click sound") { it.wire }
        settingChange(changes, "visualOffsetMs", current.settings.visualOffsetMs, incoming.settings.visualOffsetMs, "Visual offset") { "$it ms" }

        // Fields this version does not know are merged as they are, so the
        // player is told how many, since their meaning cannot be shown.
        val unknown = replacedUnknown(current.unknownFields, incoming.unknownFields) +
            replacedUnknown(current.settings.unknownFields, incoming.settings.unknownFields)
        if (unknown > 0) {
            val count = if (unknown == 1) "1 field" else "$unknown fields"
            changes.add(ImportChange(ImportChange.Section.NEWER_VERSION, "unknownFields", null, count, "$count from a newer version of the app will be replaced or added."))
        }

        return ImportDiff(changes)
    }

    private fun replacedUnknown(current: Map<String, JsonValue>, incoming: Map<String, JsonValue>): Int =
        incoming.count { (key, value) -> current[key] != value }

    private fun mergeSettings(current: PortableSettings, incoming: PortableSettings): PortableSettings {
        val unknown = LinkedHashMap(current.unknownFields)
        unknown.putAll(incoming.unknownFields)
        return PortableSettings(
            instrumentKey = incoming.instrumentKey ?: current.instrumentKey,
            a4Hz = incoming.a4Hz ?: current.a4Hz,
            displayMode = incoming.displayMode ?: current.displayMode,
            spelling = incoming.spelling ?: current.spelling,
            responseMode = incoming.responseMode ?: current.responseMode,
            noiseFloorDb = incoming.noiseFloorDb ?: current.noiseFloorDb,
            keepAwake = incoming.keepAwake ?: current.keepAwake,
            droneVoice = incoming.droneVoice ?: current.droneVoice,
            clickSound = incoming.clickSound ?: current.clickSound,
            visualOffsetMs = incoming.visualOffsetMs ?: current.visualOffsetMs,
            unknownFields = unknown,
        )
    }

    private fun <T : Any> settingChange(
        changes: MutableList<ImportChange>,
        key: String,
        before: T?,
        after: T?,
        title: String,
        show: (T) -> String,
    ) {
        if (after == null || after == before) return
        val afterText = show(after)
        val sentence = if (before == null) "$title is set to $afterText." else "$title: ${show(before)} becomes $afterText."
        changes.add(ImportChange(ImportChange.Section.SETTING, key, before?.let(show), afterText, sentence))
    }

    private fun label(range: WrittenRange) = "${Pitch.label(range.low)}..${Pitch.label(range.high)}"

    private fun trimHz(hz: Double): String = if (hz == Math.rint(hz)) hz.toInt().toString() else hz.toString()
}
