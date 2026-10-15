// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.config.DisplayMode
import ticktock.core.config.PersonalConfig
import ticktock.core.config.PersonalConfigCodec
import ticktock.core.config.PersonalConfigMerge
import ticktock.core.config.PortableSettings
import ticktock.core.detect.ResponseMode
import ticktock.core.config.Spelling
import ticktock.core.contracts.Voice
import ticktock.core.json.Json
import ticktock.core.metro.ClickSound

/**
 * Owner decision 3: the personal configuration carries the settings worth
 * carrying. The field table says which prefs those are (the portable flag),
 * and this bridge moves them in both directions. The drift tests prove the
 * two files agree on vocabulary: every portable field is written by the
 * config codec under the same key with the same spelling of its value.
 */
class PrefsPortableTest {
    private val portableKeys = PrefsSchema.fields.filter { it.portable != null }.map { it.key }.toSet()

    @Test
    fun `the portable set is the settings worth carrying and nothing device-bound`() {
        assertEquals(
            setOf(
                "instrument", "a4Hz", "displayMode", "spelling", "responseMode", "noiseFloorDb", "keepAwake",
                "earsFirst", "announceSettledNotes", "droneVoice", "clickSound", "visualOffsetMs",
            ),
            portableKeys,
        )
        // Device state and the current tool setups stay on the phone: CAP-02's
        // pause, the measured Bluetooth offset, gains, the metronome's current
        // setup and the first-run flag.
        for (key in listOf("capturePaused", "bluetoothVisualOffsetMs", "droneGain", "clickGain", "metroBpm", "firstRunComplete", "hornNickname")) {
            assertNull(key, PrefsSchema.field(key)!!.portable)
        }
    }

    @Test
    fun `export fills every portable setting and import applies every one`() {
        val prefs = Prefs(
            instrumentKey = "eb_alto",
            a4Hz = 442.0,
            displayMode = DisplayMode.CONCERT,
            spelling = Spelling.FLATS,
            responseMode = ResponseMode.SLOW,
            noiseFloorDb = -50,
            keepAwake = false,
            earsFirst = true,
            announceSettledNotes = true,
            droneVoice = Voice.ROOT_FIFTH,
            clickSound = ClickSound.BEEP,
            visualOffsetMs = 15,
            capturePaused = true,
            metroBpm = 160,
        )
        val settings = PrefsPortable.export(prefs)
        assertEquals(
            PortableSettings(
                instrumentKey = "eb_alto",
                a4Hz = 442.0,
                displayMode = DisplayMode.CONCERT,
                spelling = Spelling.FLATS,
                responseMode = ResponseMode.SLOW,
                noiseFloorDb = -50,
                keepAwake = false,
                earsFirst = true,
                announceSettledNotes = true,
                droneVoice = Voice.ROOT_FIFTH,
                clickSound = ClickSound.BEEP,
                visualOffsetMs = 15,
            ),
            settings,
        )

        // Applying the export onto fresh prefs reproduces the portable fields
        // and leaves the device-bound ones at their defaults.
        val applied = PrefsPortable.apply(Prefs(), settings)
        for (field in PrefsSchema.fields) {
            val expected = if (field.portable != null) field.get(prefs) else field.get(Prefs())
            assertEquals(field.key, expected, field.get(applied))
        }
    }

    @Test
    fun `a sparse import touches only what the file says`() {
        val current = Prefs(a4Hz = 441.0, spelling = Spelling.SHARPS, keepAwake = false)
        val applied = PrefsPortable.apply(current, PortableSettings(a4Hz = 443.0))
        assertEquals(443.0, applied.a4Hz, 0.0)
        assertEquals(Spelling.SHARPS, applied.spelling)
        assertEquals(false, applied.keepAwake)
        assertEquals(current, PrefsPortable.apply(current, PortableSettings()))
    }

    @Test
    fun `the config file and the prefs file spell every portable field the same way`() {
        val prefs = Prefs()
        val fromCodec = PersonalConfigCodec.toJson(PersonalConfig(settings = PrefsPortable.export(prefs)))["settings"]!!.obj!!
        val fromSchema = PrefsSchema.toJson(prefs)
        assertEquals("the codec writes exactly the portable keys", portableKeys, fromCodec.keys)
        for (key in portableKeys) {
            assertEquals(key, fromSchema[key], fromCodec[key])
        }
        // And the codec reads back what the schema wrote for those keys.
        val settingsObject = Json.obj(*portableKeys.map { it to fromSchema[it]!! }.toTypedArray())
        val file = Json.obj("format" to Json.str(PersonalConfig.FORMAT), "version" to Json.num(PersonalConfig.VERSION), "settings" to settingsObject)
        val parsed = PersonalConfigCodec.parse(Json.write(file))
        assertTrue(parsed.toString(), parsed is ticktock.core.config.ParseResult.Ok)
        val back = (parsed as ticktock.core.config.ParseResult.Ok).config.settings
        assertEquals(emptyMap<String, Any>(), back.unknownFields)
        assertEquals(prefs, PrefsPortable.apply(Prefs(a4Hz = 430.0, earsFirst = true), back))
    }

    @Test
    fun `a4 range is one number in the schema and the config`() {
        assertEquals(PrefsSchema.A4_HZ, PortableSettings.A4_HZ)
    }

    @Test
    fun `an import diff names the new portable fields in sentences`() {
        val current = PrefsPortable.export(Prefs())
        val incoming = PersonalConfigMerge.merge(
            PersonalConfig(settings = current),
            PersonalConfig(settings = PortableSettings(earsFirst = true, announceSettledNotes = true)),
        )
        val diff = PersonalConfigMerge.diff(PersonalConfig(settings = current), incoming)
        assertEquals(listOf("Ears first: off becomes on.", "Announce settled notes: off becomes on."), diff.sentences())
        assertNotNull(PrefsPortable.apply(Prefs(), incoming.settings))
        assertEquals(true, PrefsPortable.apply(Prefs(), incoming.settings).earsFirst)
    }
}
