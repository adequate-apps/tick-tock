// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.config

import android.net.Uri
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.core.config.*

@RunWith(RobolectricTestRunner::class)
class ConfigurationFilesTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun `document export replaces old bytes and a strict import retains the exact configuration`() {
        val file = File(context.cacheDir, "settings.json").apply { writeText("obsolete".repeat(500)) }
        val config = PersonalConfig(hornProfiles = listOf(HornProfile("clarinet", "clar_bb", "B♭ horn")), settings = PortableSettings(a4Hz = 442.0))
        val bytes = PersonalConfigCodec.write(config).toByteArray(Charsets.UTF_8)
        ConfigurationFiles.write(context, Uri.fromFile(file), bytes)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(config, (ConfigurationFiles.read(context, Uri.fromFile(file)) as ParseResult.Ok).config)
    }

    @Test fun `oversized and malformed UTF-8 documents are refused before parsing`() {
        val file = File(context.cacheDir, "invalid.json")
        for (bytes in listOf(ByteArray(ConfigurationFiles.MAX_BYTES + 1) { ' '.code.toByte() }, byteArrayOf(0xC3.toByte(), 0x28))) {
            file.writeBytes(bytes)
            try { ConfigurationFiles.read(context, Uri.fromFile(file)); fail("invalid document was accepted") }
            catch (expected: Exception) { }
        }
        file.writeText("""{"format":"ticktock-personal-config","version":99}""")
        assertTrue(ConfigurationFiles.read(context, Uri.fromFile(file)) is ParseResult.Failed)
    }
}
