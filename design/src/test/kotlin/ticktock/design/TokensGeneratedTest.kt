// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import org.junit.Assert.assertEquals
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
        assertEquals(0xFF0000A8.toInt(), GridlineColors.active.value.shr(32).toInt())
        assertEquals(0xFFC6C6C6.toInt(), GridlineColors.surface.value.shr(32).toInt())
        assertEquals(0xFFFFFFFF.toInt(), GridlineColors.field.value.shr(32).toInt())
        assertEquals(0xFF101010.toInt(), GridlineColors.borderStrong.value.shr(32).toInt())
    }

    @Test
    fun `corners are square and relief is one unit`() {
        assertEquals(0f, GridlineSpacing.radius.value)
        assertEquals(1f, GridlineRelief.frameStrong.width.value)
        assertEquals(GridlineColors.bevelLight, GridlineRelief.reliefRaised.topLeft)
        assertEquals(GridlineColors.bevelDark, GridlineRelief.reliefPressed.topLeft)
    }
}
