// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.catalogue.Catalogue
import ticktock.core.config.DisplayMode
import ticktock.core.config.ResponseMode
import ticktock.core.config.Spelling
import ticktock.core.contracts.Voice
import ticktock.core.json.Json
import ticktock.core.json.JsonValue
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound

/**
 * The prefs field table (SES-11): one list of fields with a key, a default, a
 * range and a portable flag, from which the normaliser and the writer are
 * derived. These tests pin the defaults the specification names, the lenient
 * read rule (an invalid value becomes its default and is noted, never thrown),
 * and the round trip of fields this version does not know (SES-12).
 */
class PrefsSchemaTest {
    private fun normalise(json: String): NormalisedPrefs = PrefsSchema.normalise(Json.parse(json).obj!!)

    private fun ok(text: String): NormalisedPrefs = PrefsSchema.read(text).let {
        (it as? ReadResult.Ok ?: throw AssertionError("expected Ok, got $it")).result
    }

    @Test
    fun `an empty blob yields the documented defaults with nothing to note`() {
        val result = normalise("{}")
        assertEquals(emptyList<String>(), result.notes)
        assertNull(result.storedVersion)
        val p = result.prefs
        assertEquals(Prefs(), p)
        assertEquals(Catalogue.DEFAULT_KEY, p.instrumentKey)
        assertEquals(440.0, p.a4Hz, 0.0)
        assertEquals(DisplayMode.WRITTEN, p.displayMode)
        assertEquals(Spelling.AUTO, p.spelling)
        assertEquals(ResponseMode.NORMAL, p.responseMode)
        assertEquals(-45, p.noiseFloorDb)
        assertEquals(true, p.keepAwake)
        assertEquals(false, p.capturePaused)
        assertEquals(false, p.earsFirst)
        assertEquals(false, p.announceSettledNotes)
        assertEquals(false, p.includeDroneRuns)
        assertEquals(false, p.firstRunComplete)
        assertEquals("", p.hornNickname)
        assertEquals("", p.lastEquipment)
        assertEquals(Voice.WARM, p.droneVoice)
        assertEquals(0.5f, p.droneGain)
        assertNull(p.droneMidi)
        assertEquals(ClickSound.WOOD, p.clickSound)
        assertEquals(0.8f, p.clickGain)
        assertEquals(0, p.visualOffsetMs)
        assertNull(p.bluetoothVisualOffsetMs)
        assertEquals(100, p.metroBpm)
        assertEquals(4, p.metroBeatsPerBar)
        assertEquals(4, p.metroBeatUnit)
        assertEquals(false, p.metroCompoundPulse)
        assertEquals(listOf(Accent.ACCENT, Accent.NORMAL, Accent.NORMAL, Accent.NORMAL), p.metroAccents)
        assertEquals(1, p.metroSubdivision)
        assertEquals(1, p.metroBarsOn)
        assertEquals(0, p.metroBarsOff)
        assertEquals(emptyMap<String, JsonValue>(), p.unknownFields)
    }

    /**
     * One non-default sample per key. The table test below fails when a field
     * is added here without a sample, or a sample names a key the table lacks,
     * so the two lists stay in step.
     */
    private val samples: Map<String, Any?> = mapOf(
        "instrument" to "eb_alto",
        "a4Hz" to 442.0,
        "displayMode" to DisplayMode.CONCERT,
        "spelling" to Spelling.FLATS,
        "responseMode" to ResponseMode.SLOW,
        "noiseFloorDb" to -60,
        "keepAwake" to false,
        "capturePaused" to true,
        "earsFirst" to true,
        "announceSettledNotes" to true,
        "includeDroneRuns" to true,
        "firstRunComplete" to true,
        "hornNickname" to "Shop tenor",
        "lastEquipment" to "Link 7*, Rico 3",
        "droneVoice" to Voice.SINE,
        "droneGain" to 0f,
        "droneMidi" to 58,
        "clickSound" to ClickSound.BEEP,
        "clickGain" to 1f,
        "visualOffsetMs" to 15,
        "bluetoothVisualOffsetMs" to 180,
        "metroBpm" to 132,
        "metroBeatsPerBar" to 7,
        "metroBeatUnit" to 8,
        "metroCompoundPulse" to true,
        "metroAccents" to listOf(Accent.ACCENT, Accent.NORMAL, Accent.SOFT, Accent.MUTE, Accent.NORMAL, Accent.NORMAL, Accent.ACCENT),
        "metroSubdivision" to 3,
        "metroBarsOn" to 3,
        "metroBarsOff" to 1,
    )

    @Test
    fun `SES-11 one table covers every Prefs property exactly once`() {
        val keys = PrefsSchema.fields.map { it.key }
        assertEquals("duplicate keys", keys.toSet().size, keys.size)
        assertEquals("every key has a sample and every sample names a field", samples.keys.sorted(), keys.sorted())
        assertTrue(PrefsSchema.VERSION_KEY !in keys)

        val declared = Prefs::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
        val properties = declared.map { it.name }.toSet() - setOf("unknownFields")
        val touched = HashSet<String>()
        val base = Prefs()
        for (field in PrefsSchema.fields) {
            val changed = setAny(field, base, samples.getValue(field.key))
            assertNotEquals("${field.key}: the sample must differ from the default", base, changed)
            assertEquals(field.key, samples.getValue(field.key), field.get(changed))
            val moved = declared.filter { it.get(base) != it.get(changed) }.map { it.name }
            assertEquals("${field.key} must move exactly one property", 1, moved.size)
            assertTrue("${field.key} shares a property with another field", touched.add(moved.single()))
        }
        assertEquals("properties with no field in the table", emptySet<String>(), properties - touched)
    }

    @Test
    fun `fresh prefs build a valid metronome setup`() {
        val setup = Prefs().metroSetup()
        assertEquals(100, setup.bpm)
        assertEquals(4, setup.accents.size)
        assertEquals(1, setup.barsOn)
    }

    @Test
    fun `SES-12 a full round trip keeps every field and every unknown field`() {
        var prefs = Prefs()
        for (field in PrefsSchema.fields) prefs = setAny(field, prefs, samples.getValue(field.key))
        prefs = prefs.copy(unknownFields = linkedMapOf("futureKnob" to Json.arr(listOf(Json.num(1), Json.num(2))), "theme" to Json.str("gray")))

        val text = PrefsSchema.write(prefs)
        assertTrue(text, text.startsWith("{\n  \"schemaVersion\": ${PrefsSchema.VERSION},\n  \"instrument\": \"eb_alto\","))
        val back = ok(text)
        assertEquals(prefs, back.prefs)
        assertEquals(emptyList<String>(), back.notes)
        assertEquals(PrefsSchema.VERSION, back.storedVersion)
        assertEquals(text, PrefsSchema.write(back.prefs))

        // Unknown fields come after the known ones, as read.
        val written = Json.parse(text).obj!!
        assertEquals(listOf("futureKnob", "theme"), written.keys.toList().takeLast(2))
        assertEquals(Json.parse("[1, 2]"), written["futureKnob"])
    }

    @Test
    fun `SES-13 a drone volume of zero stays zero, and the voice and note come back exactly`() {
        val text = PrefsSchema.write(Prefs(droneGain = 0f, droneVoice = Voice.ROOT_FIFTH, droneMidi = 46))
        val back = ok(text).prefs
        assertEquals(0f, back.droneGain)
        assertEquals(Voice.ROOT_FIFTH, back.droneVoice)
        assertEquals(46, back.droneMidi)
        assertEquals(emptyList<String>(), normalise("""{"droneGain": 0, "clickGain": 0}""").notes)
        assertEquals(0f, normalise("""{"clickGain": 0}""").prefs.clickGain)
    }

    @Test
    fun `POL-04 a response mode the app does not know becomes Normal`() {
        val result = normalise("""{"responseMode": "turbo"}""")
        assertEquals(ResponseMode.NORMAL, result.prefs.responseMode)
        val note = result.notes.single()
        assertTrue(note, note.startsWith("responseMode:"))
        assertTrue(note, note.contains("\"turbo\"") && note.contains("normal"))
        assertEquals(ResponseMode.NORMAL, normalise("""{"responseMode": 2}""").prefs.responseMode)
        assertEquals(ResponseMode.NORMAL, normalise("""{"responseMode": null}""").prefs.responseMode)
    }

    @Test
    fun `an invalid value becomes its default and is noted by key, never thrown`() {
        val result = normalise(
            """
            {"instrument": "kazoo", "a4Hz": "442", "noiseFloorDb": -5, "keepAwake": "yes",
             "droneGain": 1.5, "droneMidi": 300, "visualOffsetMs": 2.5, "bluetoothVisualOffsetMs": 9000,
             "metroBpm": 1000, "metroBeatUnit": 3, "metroSubdivision": 0, "metroBarsOff": -1,
             "hornNickname": 7, "clickSound": "cowbell"}
            """.trimIndent(),
        )
        val p = result.prefs
        assertEquals(Catalogue.DEFAULT_KEY, p.instrumentKey)
        assertEquals(440.0, p.a4Hz, 0.0)
        assertEquals(-45, p.noiseFloorDb)
        assertEquals(true, p.keepAwake)
        assertEquals(0.5f, p.droneGain)
        assertNull(p.droneMidi)
        assertEquals(0, p.visualOffsetMs)
        assertNull(p.bluetoothVisualOffsetMs)
        assertEquals(100, p.metroBpm)
        assertEquals(4, p.metroBeatUnit)
        assertEquals(1, p.metroSubdivision)
        assertEquals(0, p.metroBarsOff)
        assertEquals("", p.hornNickname)
        assertEquals(ClickSound.WOOD, p.clickSound)
        val noted = result.notes.map { it.substringBefore(':') }.sorted()
        assertEquals(
            listOf(
                "a4Hz", "bluetoothVisualOffsetMs", "clickSound", "droneGain", "droneMidi", "hornNickname", "instrument",
                "keepAwake", "metroBarsOff", "metroBeatUnit", "metroBpm", "metroSubdivision", "noiseFloorDb", "visualOffsetMs",
            ),
            noted,
        )
        val a4 = result.notes.first { it.startsWith("a4Hz:") }
        assertTrue(a4, a4.contains("415") && a4.contains("466") && a4.contains("440"))
        val unit = result.notes.first { it.startsWith("metroBeatUnit:") }
        assertTrue(unit, unit.contains("2, 4, 8, 16"))
    }

    @Test
    fun `A4W-01 the A4 field accepts 415 to 466 and nothing outside`() {
        assertEquals(415.0, normalise("""{"a4Hz": 415}""").prefs.a4Hz, 0.0)
        assertEquals(466.0, normalise("""{"a4Hz": 466}""").prefs.a4Hz, 0.0)
        assertEquals(442.5, normalise("""{"a4Hz": 442.5}""").prefs.a4Hz, 0.0)
        assertEquals(440.0, normalise("""{"a4Hz": 414.9}""").prefs.a4Hz, 0.0)
        assertEquals(440.0, normalise("""{"a4Hz": 466.1}""").prefs.a4Hz, 0.0)
        assertEquals(415.0..466.0, PrefsSchema.A4_HZ)
    }

    @Test
    fun `text fields are trimmed and capped`() {
        val p = normalise("""{"hornNickname": "  Horn B  ", "lastEquipment": "${"x".repeat(200)}"}""")
        assertEquals("Horn B", p.prefs.hornNickname)
        assertEquals(PrefsSchema.TEXT_MAX, p.prefs.lastEquipment.length)
        assertEquals(listOf("lastEquipment"), p.notes.map { it.substringBefore(':') })
    }

    @Test
    fun `the accent row always has one entry per beat`() {
        val short = normalise("""{"metroBeatsPerBar": 6, "metroAccents": ["accent", "soft"]}""")
        assertEquals(listOf(Accent.ACCENT, Accent.SOFT, Accent.NORMAL, Accent.NORMAL, Accent.NORMAL, Accent.NORMAL), short.prefs.metroAccents)
        assertEquals(listOf("metroAccents"), short.notes.map { it.substringBefore(':') })
        assertEquals(6, short.prefs.metroSetup().beatsPerBar)

        val long = normalise("""{"metroBeatsPerBar": 2, "metroAccents": ["accent", "normal", "mute"]}""")
        assertEquals(listOf(Accent.ACCENT, Accent.NORMAL), long.prefs.metroAccents)

        val bad = normalise("""{"metroAccents": ["accent", "loud", 3, "mute"]}""")
        assertEquals(listOf(Accent.ACCENT, Accent.NORMAL, Accent.NORMAL, Accent.MUTE), bad.prefs.metroAccents)
        assertEquals(1, bad.notes.size)

        val absent = normalise("""{"metroBeatsPerBar": 3}""")
        assertEquals(listOf(Accent.ACCENT, Accent.NORMAL, Accent.NORMAL), absent.prefs.metroAccents)
        assertEquals(emptyList<String>(), absent.notes)
    }

    @Test
    fun `a blob from a newer schema is read for what this version knows and noted`() {
        val result = normalise("""{"schemaVersion": ${PrefsSchema.VERSION + 1}, "a4Hz": 442, "ringTone": "bell"}""")
        assertEquals(PrefsSchema.VERSION + 1, result.storedVersion)
        assertEquals(442.0, result.prefs.a4Hz, 0.0)
        assertEquals(setOf("ringTone"), result.prefs.unknownFields.keys)
        val note = result.notes.single()
        assertTrue(note, note.contains("newer"))
        assertTrue(PrefsSchema.write(result.prefs).contains("\"schemaVersion\": ${PrefsSchema.VERSION}"))
    }

    @Test
    fun `a blob that is not a JSON object is reported as corrupt with a reason`() {
        val corrupt = PrefsSchema.read("{not json") as ReadResult.Corrupt
        assertTrue(corrupt.reason, corrupt.reason.contains("JSON"))
        val list = PrefsSchema.read("[1, 2]") as ReadResult.Corrupt
        assertTrue(list.reason, list.reason.contains("object"))
        assertTrue(PrefsSchema.read("") is ReadResult.Corrupt)
    }

    @Test
    fun `every field states its range in a sentence for the settings screen and the notes`() {
        for (field in PrefsSchema.fields) {
            assertTrue(field.key, field.allowed.isNotBlank())
        }
        assertEquals("415 to 466", PrefsSchema.field("a4Hz")!!.allowed)
        assertEquals("one of fast, normal, slow", PrefsSchema.field("responseMode")!!.allowed)
        assertEquals("one of 2, 4, 8, 16", PrefsSchema.field("metroBeatUnit")!!.allowed)
        assertNull(PrefsSchema.field("nothing"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun setAny(field: PrefField<*>, prefs: Prefs, value: Any?): Prefs = (field as PrefField<Any?>).set(prefs, value)
}
