// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.testing.TEST_SUPPORT_MODULE
import java.io.File

class CentsBandTest {
    @Test
    fun `BND-01 rounds half away from zero and always signs`() {
        assertEquals(5, Cents.shown(5.4))
        assertEquals(6, Cents.shown(5.5))
        assertEquals(12, Cents.shown(12.4))
        assertEquals(13, Cents.shown(12.5))
        assertEquals(-5, Cents.shown(-5.4))
        assertEquals(-6, Cents.shown(-5.5))
        assertEquals(-13, Cents.shown(-12.6))
        assertEquals(0, Cents.shown(-0.4))
        assertEquals("+0", Cents.format(Cents.shown(-0.4)))
        // The largest double below one half: adding 0.5 first would round it
        // up to 1.0 before the floor, so -0.4999... would print as -1.
        assertEquals(0, Cents.shown(0.49999999999999994))
        assertEquals(0, Cents.shown(-0.49999999999999994))
        assertEquals("+0", Cents.format(Cents.shown(-0.49999999999999994)))
        assertEquals(5, Cents.shown(5.499999999999999))
        assertEquals(12, Cents.shown(12.499999999999998))
        assertEquals(-12, Cents.shown(-12.499999999999998))
        assertEquals("+5", Cents.format(5))
        assertEquals("-13", Cents.format(-13))
    }

    @Test
    fun `BND-02 bands are judged on the shown integer`() {
        assertEquals(CentsBand.IN_TUNE, CentsBand.of(0))
        assertEquals(CentsBand.IN_TUNE, CentsBand.of(5))
        assertEquals(CentsBand.IN_TUNE, CentsBand.of(-5))
        assertEquals(CentsBand.CLOSE, CentsBand.of(6))
        assertEquals(CentsBand.CLOSE, CentsBand.of(12))
        assertEquals(CentsBand.CLOSE, CentsBand.of(-12))
        assertEquals(CentsBand.OFF, CentsBand.of(13))
        assertEquals(CentsBand.OFF, CentsBand.of(-13))
        assertEquals(CentsBand.OFF, CentsBand.of(50))
    }

    @Test
    fun `BND-03 colour agrees with digits`() {
        val cases = listOf(
            5.4 to ("+5" to CentsBand.IN_TUNE),
            5.5 to ("+6" to CentsBand.CLOSE),
            12.4 to ("+12" to CentsBand.CLOSE),
            12.5 to ("+13" to CentsBand.OFF),
            -5.4 to ("-5" to CentsBand.IN_TUNE),
            -12.6 to ("-13" to CentsBand.OFF),
        )
        for ((raw, expected) in cases) {
            val shown = Cents.shown(raw)
            assertEquals("digits for $raw", expected.first, Cents.format(shown))
            assertEquals("band for $raw", expected.second, CentsBand.of(shown))
        }
    }

    @Test
    fun `BND-05 no data shows a dash and is off`() {
        assertNull(Cents.shown(Double.NaN))
        assertNull(Cents.shown(Double.POSITIVE_INFINITY))
        assertNull(Cents.shown(Double.NEGATIVE_INFINITY))
        assertNull(Cents.shown(3.0, hz = 0.0))
        assertNull(Cents.shown(3.0, hz = -440.0))
        assertNull(Cents.shown(3.0, hz = Double.NaN))
        assertEquals("–", Cents.format(null))
        assertEquals(CentsBand.OFF, CentsBand.of(null))
    }

    /**
     * BND-04: the thresholds live in CentsBand and nowhere else. Any main source in
     * any module that compares against a band literal (5, 12, or a 1.x value 3, 15,
     * 10, 25) under any name, compares a cents-ish value against any literal, or
     * declares a cents constant or the threshold names is an offender, unless
     * tools/band-literal-allowlist.txt names that exact line as unrelated.
     */
    @Test
    fun `BND-04 no other band threshold literal exists in any module`() {
        val repoRoot = File("..").canonicalFile
        val mainSources = repoRoot.listFiles { f -> f.isDirectory && f.name != TEST_SUPPORT_MODULE }
            .orEmpty()
            .flatMap { module -> listOf("src/main", "src/commonMain", "src/jvmMain").map { File(module, it) }.filter { it.isDirectory } }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
        check(mainSources.any { it.name == "CentsBand.kt" }) { "scan did not reach CentsBand.kt from ${repoRoot.path}" }

        val allow = BandThresholdGuard.allowlist(File(repoRoot, "tools/band-literal-allowlist.txt"))
        val offenders = mainSources
            .filter { it.name != "CentsBand.kt" }
            .flatMap { file -> BandThresholdGuard.offenders(file, file.relativeTo(repoRoot).path, allow) }
        assertEquals("band thresholds outside CentsBand.kt", emptyList<String>(), offenders)
    }

    /**
     * The shapes BND-04 has to catch, whatever the variable is called: a
     * duplicated threshold reads exactly like CentsBand's own comparison once
     * the parameter is named "shown" or "value" instead of "cents".
     */
    @Test
    fun `BND-04 the rule catches a threshold under any name`() {
        val caught = listOf(
            "const val CLOSE_CENTS = 12",
            "val inTuneCents: Int = 5",
            "if (abs(cents) <= 5) green() else if (abs(cents) <= 12) amber()",
            "fun readingColour(shown: Int): Int = if (kotlin.math.abs(shown) <= 5) 1 else if (kotlin.math.abs(shown) <= 12) 2 else 3",
            "val tone = when { abs(value) < 3 -> Tone.IN; abs(value) < 15 -> Tone.CLOSE; else -> Tone.OFF }",
            "return 10 >= abs(delta)",
            "if (deviation > 25.0) return Band.OFF",
            "val IN_TUNE_MAX = 5",
        )
        for (line in caught) assertTrue("should offend: $line", BandThresholdGuard.offends(line))
    }

    @Test
    fun `BND-04 the rule leaves ordinary code alone`() {
        val allowed = listOf(
            "// abs(shown) <= 5 is explained here, in a comment",
            "val bars = 101",
            "for (i in 0 until 5) draw(i)",
            "if (count == 0) return",
            "val label = \"\${cents}\"",
            "val colour = CentsBand.of(shown)",
            "items.map { it -> 5 }",
            "val shifted = bits shr 5",
            "if (index >= size) throw IndexOutOfBoundsException()",
        )
        for (line in allowed) assertFalse("should pass: $line", BandThresholdGuard.offends(line))
    }
}
