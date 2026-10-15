// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tokens.kt is generated from the token sheet; this test fails when someone
 * edits the Kotlin by hand or changes the CSS without regenerating, so the
 * app and the design system can never disagree about a value.
 */
class TokensGeneratedTest {
    // The unit-test working directory is the module; the generator sits two levels up.
    private val root = File("..").canonicalFile
    private val generator = File(root, "tools/gen-tokens/gen_tokens.py")
    private val tokens = File(root, "design/src/main/kotlin/ticktock/design/Tokens.kt")

    @Test
    fun `the committed Tokens kt matches the generator output`() {
        assertTrue("generator missing at $generator", generator.isFile)
        val process = ProcessBuilder("python3", "-I", generator.path, "--check", "--output", tokens.path)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("generator did not finish", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals("gen_tokens.py --check failed:\n$output", 0, process.exitValue())
    }

    @Test
    fun `the generated colours carry the four Gridline anchors`() {
        val gridline = GridlineThemes.gridline
        assertEquals(0xFF0000A8.toInt(), gridline.active.value.shr(32).toInt())
        assertEquals(0xFFC6C6C6.toInt(), gridline.surface.value.shr(32).toInt())
        assertEquals(0xFFFFFFFF.toInt(), gridline.field.value.shr(32).toInt())
        assertEquals(0xFF101010.toInt(), gridline.borderStrong.value.shr(32).toInt())
    }

    @Test
    fun `four themes in picker order, and Pit is black with the accent only as a line`() {
        assertEquals(listOf("Gridline", "Daylight", "Dark", "Pit"), GridlineThemes.all.map { it.name })
        assertEquals(listOf(false, false, true, true), GridlineThemes.all.map { it.isDark })
        val pit = GridlineThemes.pit
        for (fill in listOf(pit.surface, pit.field, pit.controlFace, pit.titleBg, pit.selectionBg)) assertEquals(Color.Black, fill)
        assertNotEquals(pit.selectionBg, pit.selectionOutline)
    }

    @Test
    fun `corners are square and relief is one unit`() {
        assertEquals(0f, GridlineSpacing.radius.value)
        for (palette in GridlineThemes.all) {
            assertEquals(1f, palette.relief.frameStrong.width.value)
            assertEquals(palette.bevelLight, palette.relief.reliefRaised.topLeft)
            assertEquals(palette.bevelDark, palette.relief.reliefPressed.topLeft)
        }
    }
}
