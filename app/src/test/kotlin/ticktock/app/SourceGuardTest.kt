// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The component consistency gate's source rules (PLAN section 1). Screen code
 * in :app builds from :design components, so a hand-made control or a size
 * chosen on the spot cannot drift from the design system:
 * - no raw `clickable`, `selectable`, `toggleable` or `pointerInput`;
 * - no `dp` or `sp` literal, no raw `TextStyle`, no font size;
 * - every status-bar string is one or two words (the status bar states facts
 *   in 1–2 words; sentences belong to pane notices).
 */
class SourceGuardTest {
    private val sources = listOf("src/main/kotlin", "src/debug/kotlin").map(::File)

    private val rules = listOf(
        "a hand-made control" to Regex("""\.(clickable|selectable|toggleable|combinedClickable)\s*[({]|\bpointerInput\s*\("""),
        "a size literal" to Regex("""\b\d+(\.\d+)?\s*\.\s*(dp|sp)\b"""),
        "a raw text style" to Regex("""\bTextStyle\s*\(|\bfontSize\s*="""),
    )

    private fun offenders(lines: Sequence<Pair<String, String>>): List<String> =
        lines.flatMap { (where, line) ->
            val code = line.substringBefore("//")
            rules.filter { (_, rule) -> rule.containsMatchIn(code) }.map { (what, _) -> "$where: $what: ${line.trim()}" }
        }.toList()

    @Test
    fun `screen code uses design components and design sizes only`() {
        assertTrue("test must run with the module directory as working directory", sources.first().isDirectory)
        val lines = sources.filter { it.isDirectory }.asSequence()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .flatMap { file -> file.readLines().asSequence().mapIndexed { i, line -> "${file.path}:${i + 1}" to line } }
        assertEquals(emptyList<String>(), offenders(lines))
    }

    @Test
    fun `the guard catches the shapes it is meant to`() {
        val planted = listOf(
            "Modifier.clickable { go() }",
            "Modifier.pointerInput(Unit) { }",
            "Modifier.selectable(selected = true, onClick = {})",
            "Modifier.padding(12.dp)",
            "val big = 2.5.sp",
            "TextStyle(color = Color.Red)",
            "style.copy(fontSize = big)",
        )
        assertEquals(planted.size, offenders(planted.asSequence().map { "planted" to it }).size)
        val clean = listOf("Modifier.padding(GridlineSpacing.sp2)", "// 12.dp in a comment is fine", "GlText(text = label)")
        assertEquals(emptyList<String>(), offenders(clean.asSequence().map { "clean" to it }))
    }

    @Test
    fun `status bar strings are one or two words`() {
        val strings = File("src/main/res/values/strings.xml")
        assertTrue(strings.isFile)
        val status = Regex("""<string name="(status_[a-z_]+)">([^<]*)</string>""").findAll(strings.readText())
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("no status strings found", status.isNotEmpty())
        val long = status.filter { (_, text) -> text.trim().split(Regex("""\s+""")).size > 2 }
        assertEquals("status segments longer than two words", emptyList<Pair<String, String>>(), long)
    }
}
