// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
     * any module that compares a cents-ish value against a whole-number literal is
     * an offender, as is a second definition of the threshold names. The 1.x sets
     * (3/15, 5/15, 10/25) are caught by the same rule.
     */
    @Test
    fun `BND-04 no other band threshold literal exists in any module`() {
        val repoRoot = File("..").canonicalFile
        val mainSources = repoRoot.listFiles { f -> f.isDirectory && File(f, "src/main").isDirectory }
            .orEmpty()
            .flatMap { module -> File(module, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" } }
        check(mainSources.any { it.name == "CentsBand.kt" }) { "scan did not reach CentsBand.kt from ${repoRoot.path}" }

        val comparison = Regex("""(<=|>=|<|>|==)\s*-?\d+\b""")
        val centsish = Regex("""(?i)cents|band|inTune|in_tune""")
        val thresholdNames = Regex("""\b(IN_TUNE_MAX|CLOSE_MAX)\s*=""")

        val offenders = mainSources
            .filter { it.name != "CentsBand.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//")
                    val suspicious = (centsish.containsMatchIn(code) && comparison.containsMatchIn(code)) ||
                        thresholdNames.containsMatchIn(code)
                    if (suspicious) "${file.relativeTo(repoRoot).path}:${index + 1}: ${line.trim()}" else null
                }
            }
        assertEquals("band thresholds outside CentsBand.kt", emptyList<String>(), offenders)
    }
}
