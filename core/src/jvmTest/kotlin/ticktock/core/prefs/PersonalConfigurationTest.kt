// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.prefs

import org.junit.Assert.*
import org.junit.Test
import ticktock.core.config.*
import ticktock.core.json.Json
import ticktock.core.ranges.UserRangeOverride
import ticktock.core.ranges.WrittenRange

class PersonalConfigurationTest {
    @Test fun `configuration payload and portable settings survive one atomic preferences round trip`() {
        val old = Prefs(theme = Theme.PIT, micAsked = true, bluetoothVisualOffsetMs = 123, capturePaused = true,
            droneGain = 0f, metroBpm = 132, personalConfig = PersonalConfig(
                hornProfiles = listOf(HornProfile("old", "flute_c", "Silver flute")),
                unknownFields = mapOf("oldField" to Json.num(1))))
        val incoming = PersonalConfig(
            rangeOverrides = mapOf("eb_alto" to UserRangeOverride(WrittenRange(57, 93))),
            hornProfiles = listOf(HornProfile("new", "eb_alto", "Shop alto")),
            settings = PortableSettings(instrumentKey = "eb_alto", a4Hz = 442.0, noiseFloorDb = -60,
                unknownFields = mapOf("futureSwitch" to Json.bool(true))),
            unknownFields = mapOf("futureProfile" to Json.arr(listOf(Json.str("saved")))))
        val imported = PersonalConfiguration.apply(old, incoming)
        val restored = (PrefsSchema.read(PrefsSchema.write(imported)) as ReadResult.Ok).result.prefs
        assertEquals(imported, restored)
        assertEquals(442.0, restored.a4Hz, 0.0)
        assertEquals(-60, restored.noiseFloorDb)
        assertEquals("eb_alto", restored.instrumentKey)
        assertEquals(Theme.PIT, restored.theme)
        assertTrue(restored.micAsked && restored.capturePaused)
        assertEquals(123, restored.bluetoothVisualOffsetMs)
        assertEquals(0f, restored.droneGain)
        assertEquals(132, restored.metroBpm)
        val exported = PersonalConfiguration.snapshot(restored.copy(a4Hz = 445.0))
        assertEquals(445.0, exported.settings.a4Hz!!, 0.0)
        assertEquals(incoming.rangeOverrides, exported.rangeOverrides)
        assertEquals(listOf("old", "new"), exported.hornProfiles.map { it.id })
        assertEquals(incoming.settings.unknownFields, exported.settings.unknownFields)
        assertEquals(setOf("oldField", "futureProfile"), exported.unknownFields.keys)
        assertEquals(PortableSettings(unknownFields = incoming.settings.unknownFields), restored.personalConfig.settings)
        assertTrue(PersonalConfigMerge.diff(PersonalConfiguration.snapshot(restored), incoming).isEmpty)
        val encoded = PersonalConfigCodec.write(exported)
        for (deviceKey in listOf("bluetoothVisualOffsetMs", "theme", "capturePaused", "micAsked", "droneGain", "metroBpm")) {
            assertFalse(encoded.contains("\"$deviceKey\""))
        }
    }

    @Test fun `invalid stored personal payload is reported without losing unrelated preferences`() {
        val result = PrefsSchema.read("""{"a4Hz":442,"theme":"pit","personalConfig":{"format":"ticktock-personal-config","version":1,"rangeOverrides":{"eb_alto":{"keyedLow":99,"keyedHigh":57}}}}""") as ReadResult.Ok
        assertEquals(PersonalConfig(), result.result.prefs.personalConfig)
        assertEquals(442.0, result.result.prefs.a4Hz, 0.0)
        assertEquals(Theme.PIT, result.result.prefs.theme)
        assertTrue(result.result.notes.any { it.startsWith("personalConfig:") })
    }
}
