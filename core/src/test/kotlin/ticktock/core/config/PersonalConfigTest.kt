// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.Voice
import ticktock.core.json.Json
import ticktock.core.metro.ClickSound
import ticktock.core.ranges.UserRangeOverride
import ticktock.core.ranges.WrittenRange

class PersonalConfigTest {
    private val full = PersonalConfig(
        rangeOverrides = linkedMapOf(
            "eb_alto" to UserRangeOverride(WrittenRange(57, 93)),
            "bb_tenor" to UserRangeOverride(WrittenRange(58, 96)),
        ),
        hornProfiles = listOf(
            HornProfile("h1", "eb_alto", "Silver alto"),
            HornProfile("h2", "bb_tenor", "Shop tenor"),
        ),
        settings = PortableSettings(
            instrumentKey = "eb_alto",
            a4Hz = 442.0,
            displayMode = DisplayMode.WRITTEN,
            spelling = Spelling.FLATS,
            responseMode = ResponseMode.SLOW,
            noiseFloorDb = -50,
            keepAwake = true,
            droneVoice = Voice.ROOT_FIFTH,
            clickSound = ClickSound.BEEP,
            visualOffsetMs = 15,
        ),
    )

    private fun ok(text: String): ParseResult.Ok = PersonalConfigCodec.parse(text).let {
        it as? ParseResult.Ok ?: throw AssertionError("expected Ok, got $it")
    }

    private fun failed(text: String): ParseResult.Failed = PersonalConfigCodec.parse(text).let {
        it as? ParseResult.Failed ?: throw AssertionError("expected Failed, got $it")
    }

    @Test
    fun `round trip through the file format`() {
        val text = PersonalConfigCodec.write(full)
        val back = ok(text)
        assertEquals(full, back.config)
        assertEquals(emptyList<String>(), back.warnings)
        assertEquals(text, PersonalConfigCodec.write(back.config))
        assertTrue(text.startsWith("{\n  \"format\": \"ticktock-personal-config\",\n  \"version\": 1,"))
    }

    @Test
    fun `an empty file is a complete file`() {
        val back = ok("""{"format": "ticktock-personal-config", "version": 1}""")
        assertEquals(PersonalConfig(), back.config)
    }

    @Test
    fun `unknown fields are kept, reported and written back`() {
        val text = """
            {"format": "ticktock-personal-config", "version": 1,
             "settings": {"a4Hz": 441, "futureKnob": [1, 2]},
             "later": {"x": true}}
        """.trimIndent()
        val back = ok(text)
        assertEquals(441.0, back.config.settings.a4Hz)
        assertEquals(setOf("futureKnob"), back.config.settings.unknownFields.keys)
        assertEquals(setOf("later"), back.config.unknownFields.keys)
        assertEquals(2, back.warnings.size)
        assertTrue(back.warnings.all { it.contains("kept as read") })
        val again = ok(PersonalConfigCodec.write(back.config))
        assertEquals(back.config, again.config)
        val written = Json.parse(PersonalConfigCodec.write(back.config)).obj!!
        assertEquals(Json.parse("""{"x": true}"""), written["later"])
    }

    @Test
    fun `a newer version is refused with a sentence that says so`() {
        val result = failed("""{"format": "ticktock-personal-config", "version": 2, "settings": {"a4Hz": 440}}""")
        assertEquals(1, result.problems.size)
        assertEquals("version", result.problems[0].path)
        assertTrue(result.problems[0].message.contains("newer"))
    }

    @Test
    fun `a missing version, a zero version and another format are refused`() {
        assertEquals("version", failed("""{"format": "ticktock-personal-config"}""").problems.single().path)
        assertEquals("version", failed("""{"format": "ticktock-personal-config", "version": 0}""").problems.single().path)
        assertEquals("version", failed("""{"format": "ticktock-personal-config", "version": "1"}""").problems.single().path)
        assertEquals("format", failed("""{"format": "something-else", "version": 1}""").problems.single().path)
        assertEquals("format", failed("""{"version": 1}""").problems.single().path)
        assertEquals("file", failed("""[1, 2]""").problems.single().path)
        assertEquals("file", failed("""{not json""").problems.single().path)
    }

    @Test
    fun `every bad value is reported at once and nothing is applied`() {
        val text = """
            {"format": "ticktock-personal-config", "version": 1,
             "rangeOverrides": {
               "eb_alto": {"keyedLow": 90, "keyedHigh": 58},
               "kazoo": {"keyedLow": 60, "keyedHigh": 70},
               "bb_tenor": {"keyedLow": 10, "keyedHigh": 70},
               "oboe": {"keyedLow": "C4", "keyedHigh": 70}
             },
             "hornProfiles": [
               {"id": "a", "instrument": "eb_alto", "nickname": "One"},
               {"id": "a", "instrument": "eb_alto", "nickname": "Two"},
               {"id": "", "instrument": "nothing", "nickname": ""},
               "text"
             ],
             "settings": {
               "a4Hz": 429, "displayMode": "sideways", "noiseFloorDb": -5, "keepAwake": "yes",
               "visualOffsetMs": 2.5, "instrument": "kazoo", "spelling": 3
             }}
        """.trimIndent()
        val result = failed(text)
        val paths = result.problems.map { it.path }
        val expected = listOf(
            "rangeOverrides.eb_alto",
            "rangeOverrides.kazoo",
            "rangeOverrides.bb_tenor",
            "rangeOverrides.oboe.keyedLow",
            "hornProfiles[1].id",
            "hornProfiles[2].id",
            "hornProfiles[2].instrument",
            "hornProfiles[2].nickname",
            "hornProfiles[3]",
            "settings.a4Hz",
            "settings.displayMode",
            "settings.noiseFloorDb",
            "settings.keepAwake",
            "settings.visualOffsetMs",
            "settings.instrument",
            "settings.spelling",
        )
        assertEquals(expected.sorted(), paths.sorted())
        val backwards = result.problems.first { it.path == "rangeOverrides.eb_alto" }
        assertTrue(backwards.message, backwards.message.contains("F#/Gb6") && backwards.message.contains("A#/Bb3"))
        val outside = result.problems.first { it.path == "rangeOverrides.bb_tenor" }
        assertTrue(outside.message, outside.message.contains("A0..C8"))
        val mode = result.problems.first { it.path == "settings.displayMode" }
        assertTrue(mode.message, mode.message.contains("\"written\"") && mode.message.contains("\"sideways\""))
    }

    @Test
    fun `a4 accepts the whole 430 to 450 span and nothing outside it`() {
        assertEquals(430.0, ok("""{"format": "ticktock-personal-config", "version": 1, "settings": {"a4Hz": 430}}""").config.settings.a4Hz)
        assertEquals(450.0, ok("""{"format": "ticktock-personal-config", "version": 1, "settings": {"a4Hz": 450}}""").config.settings.a4Hz)
        assertEquals(442.5, ok("""{"format": "ticktock-personal-config", "version": 1, "settings": {"a4Hz": 442.5}}""").config.settings.a4Hz)
        assertEquals("settings.a4Hz", failed("""{"format": "ticktock-personal-config", "version": 1, "settings": {"a4Hz": 450.1}}""").problems.single().path)
    }

    @Test
    fun `merge lets the incoming file win and deletes nothing`() {
        val current = PersonalConfig(
            rangeOverrides = linkedMapOf("eb_alto" to UserRangeOverride(WrittenRange(58, 90)), "oboe" to UserRangeOverride(WrittenRange(58, 88))),
            hornProfiles = listOf(HornProfile("h1", "eb_alto", "Old alto"), HornProfile("h9", "oboe", "Oboe")),
            settings = PortableSettings(a4Hz = 440.0, keepAwake = false, clickSound = ClickSound.WOOD),
        )
        val merged = PersonalConfigMerge.merge(current, full)
        assertEquals(
            linkedMapOf(
                "eb_alto" to UserRangeOverride(WrittenRange(57, 93)),
                "oboe" to UserRangeOverride(WrittenRange(58, 88)),
                "bb_tenor" to UserRangeOverride(WrittenRange(58, 96)),
            ),
            merged.rangeOverrides,
        )
        assertEquals(
            listOf(HornProfile("h1", "eb_alto", "Silver alto"), HornProfile("h9", "oboe", "Oboe"), HornProfile("h2", "bb_tenor", "Shop tenor")),
            merged.hornProfiles,
        )
        assertEquals(442.0, merged.settings.a4Hz)
        assertEquals(true, merged.settings.keepAwake)
        assertEquals(ClickSound.BEEP, merged.settings.clickSound)

        val sparse = PersonalConfig(settings = PortableSettings(a4Hz = 443.0))
        val mergedSparse = PersonalConfigMerge.merge(current, sparse)
        assertEquals(443.0, mergedSparse.settings.a4Hz)
        assertEquals(false, mergedSparse.settings.keepAwake)
        assertEquals(ClickSound.WOOD, mergedSparse.settings.clickSound)
        assertEquals(current.rangeOverrides, mergedSparse.rangeOverrides)
        assertEquals(current.hornProfiles, mergedSparse.hornProfiles)
    }

    @Test
    fun `diff says what the import will change, in sentences`() {
        val current = PersonalConfig(
            rangeOverrides = linkedMapOf("eb_alto" to UserRangeOverride(WrittenRange(58, 90))),
            hornProfiles = listOf(HornProfile("h1", "eb_alto", "Old alto")),
            settings = PortableSettings(a4Hz = 440.0, keepAwake = true, droneVoice = Voice.ROOT_FIFTH),
        )
        val diff = PersonalConfigMerge.diff(current, full)
        assertEquals(
            listOf(
                "Range for Alto saxophone in Eb: A#/Bb3..F#/Gb6 becomes A3..A6.",
                "Range for Tenor saxophone in Bb: A#/Bb3..C7 replaces the default.",
                "Horn profile \"Old alto\" (Alto saxophone in Eb) becomes \"Silver alto\" (Alto saxophone in Eb).",
                "Horn profile \"Shop tenor\" (Tenor saxophone in Bb) is added.",
                "Instrument is set to Alto saxophone in Eb.",
                "A4: 440 Hz becomes 442 Hz.",
                "Note names is set to written.",
                "Spelling is set to flats.",
                "Response is set to slow.",
                "Ignore sounds quieter than is set to -50 dB.",
                "Click sound is set to beep.",
                "Visual offset is set to 15 ms.",
            ),
            diff.sentences(),
        )
        assertEquals(ImportChange.Section.SETTING, diff.changes.last().section)
        assertEquals("440 Hz", diff.changes.first { it.key == "a4Hz" }.before)
    }

    /**
     * Decision 3: an import shows what it will change before applying. Fields
     * this version does not know are merged too, so the diff has to say so;
     * ones that already hold the same value are not a change.
     */
    @Test
    fun `diff counts the unknown fields an import will replace`() {
        val current = PersonalConfig(
            settings = PortableSettings(a4Hz = 440.0, unknownFields = linkedMapOf("ringTone" to Json.str("bell"))),
            unknownFields = linkedMapOf("theme" to Json.str("gray"), "same" to Json.num(1)),
        )
        val incoming = PersonalConfig(
            settings = PortableSettings(unknownFields = linkedMapOf("ringTone" to Json.str("chime"))),
            unknownFields = linkedMapOf("theme" to Json.str("blue"), "same" to Json.num(1), "added" to Json.bool(true)),
        )
        val diff = PersonalConfigMerge.diff(current, incoming)
        assertEquals(listOf("3 fields from a newer version of the app will be replaced or added."), diff.sentences())
        val change = diff.changes.single()
        assertEquals(ImportChange.Section.NEWER_VERSION, change.section)
        assertEquals("3 fields", change.after)

        val one = PersonalConfigMerge.diff(current, PersonalConfig(unknownFields = linkedMapOf("added" to Json.bool(true))))
        assertEquals(listOf("1 field from a newer version of the app will be replaced or added."), one.sentences())
        assertTrue(PersonalConfigMerge.diff(current, PersonalConfig(unknownFields = linkedMapOf("same" to Json.num(1)))).isEmpty)
    }

    @Test
    fun `diff of an identical or empty import is empty`() {
        assertTrue(PersonalConfigMerge.diff(full, full).isEmpty)
        assertEquals(listOf("Nothing will change."), PersonalConfigMerge.diff(full, PersonalConfig()).sentences())
    }

    @Test
    fun `merge then diff is empty, so applying is idempotent`() {
        val current = PersonalConfig(settings = PortableSettings(a4Hz = 440.0))
        val merged = PersonalConfigMerge.merge(current, full)
        assertTrue(PersonalConfigMerge.diff(merged, full).isEmpty)
        assertEquals(merged, PersonalConfigMerge.merge(merged, full))
    }
}
