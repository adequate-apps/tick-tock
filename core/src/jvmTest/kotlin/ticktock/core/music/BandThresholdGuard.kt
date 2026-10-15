// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import java.io.File

/**
 * The BND-04 source rule: which lines of main-source Kotlin count as a second
 * band threshold. Kept apart from the scanning test so the rule itself can be
 * tested against crafted lines, including the shapes it must catch and the
 * ordinary code it must leave alone. tools/verify.sh carries the same rule as
 * grep patterns; tools/band-literal-allowlist.txt is shared by both.
 */
object BandThresholdGuard {
    /** The two thresholds plus the 1.x sets (3/15, 5/15, 10/25) the spec names as absent. */
    private const val BAND_LITERALS = """(3|5|10|12|15|25)(\.0+)?"""

    private val anyComparison = Regex("""(<=|>=|<|>|==)\s*-?\d+\b""")
    private val centsish = Regex("""(?i)cents|band|inTune|in_tune""")
    private val centsDeclaration = Regex("""\bval [A-Za-z_]*(Cents|CENTS)[A-Za-z_]*(\s*:\s*[A-Za-z]+)?\s*=\s*-?\d""")
    private val thresholdNames = Regex("""\b(IN_TUNE_MAX|CLOSE_MAX)\s*=""")

    // A comparison operator that is not part of an arrow, a shift or a
    // not-equals: "-> 5" and "shr 5" are not thresholds.
    private val literalOnRight = Regex("""(?<![-=<>!])(<=|>=|==|<|>)\s*-?$BAND_LITERALS\b""")
    private val literalOnLeft = Regex("""(?<![\w.])$BAND_LITERALS\s*(<=|>=|<|>)(?![>=\-])""")

    /** True when [line] (one line of code, comments stripped) defines or compares a band threshold. */
    fun offends(line: String): Boolean {
        val code = line.substringBefore("//")
        if (code.isBlank()) return false
        return thresholdNames.containsMatchIn(code) ||
            centsDeclaration.containsMatchIn(code) ||
            (centsish.containsMatchIn(code) && anyComparison.containsMatchIn(code)) ||
            literalOnRight.containsMatchIn(code) ||
            literalOnLeft.containsMatchIn(code)
    }

    /** Allowlist entries from [file]: one regex per line, comments and blanks skipped. */
    fun allowlist(file: File): List<Regex> =
        if (!file.isFile) emptyList() else file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map(::Regex)

    /** Offending lines of [file] as "path:line: text", minus lines an [allow] entry matches as "path:text". */
    fun offenders(file: File, path: String, allow: List<Regex> = emptyList()): List<String> =
        file.readLines().mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            when {
                !offends(line) -> null
                allow.any { it.containsMatchIn("$path:$trimmed") } -> null
                else -> "$path:${index + 1}: $trimmed"
            }
        }
}
