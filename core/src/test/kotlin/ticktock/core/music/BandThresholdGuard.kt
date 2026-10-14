// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import java.io.File

/**
 * The BND-04 source rule: which lines of main-source Kotlin count as a second
 * band threshold. Kept apart from the scanning test so the rule itself can be
 * tested against crafted lines, including the shapes it must catch and the
 * ordinary code it must leave alone.
 */
object BandThresholdGuard {
    private val comparison = Regex("""(<=|>=|<|>|==)\s*-?\d+\b""")
    private val centsish = Regex("""(?i)cents|band|inTune|in_tune""")
    private val thresholdNames = Regex("""\b(IN_TUNE_MAX|CLOSE_MAX)\s*=""")

    /** True when [line] (one line of code, comments stripped) defines or compares a band threshold. */
    fun offends(line: String): Boolean {
        val code = line.substringBefore("//")
        return (centsish.containsMatchIn(code) && comparison.containsMatchIn(code)) ||
            thresholdNames.containsMatchIn(code)
    }

    /** Offending lines of [file] as "path:line: text", with [path] the repo-relative name. */
    fun offenders(file: File, path: String): List<String> =
        file.readLines().mapIndexedNotNull { index, line ->
            if (offends(line)) "$path:${index + 1}: ${line.trim()}" else null
        }
}
