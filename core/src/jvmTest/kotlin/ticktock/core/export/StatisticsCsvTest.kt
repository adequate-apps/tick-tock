// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.export

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class StatisticsCsvTest {
    @Test fun `EXP-01 UTF-8 fields retain quotes commas newlines and capture labels`() {
        val input = StatisticsRow("B♭ clarinet", 442.0, "D5", "C5", 38, 3, 4.25, 6.04,
            "drone", "Reed, \"soft\"\nMouthpiece", "Shop\r\ntest", "2026-10-15T12:00:00Z")
        val parsed = parse(StatisticsCsv.encode(listOf(input)).decodeToString())
        assertEquals(listOf("instrument", "A4", "written note", "concert note", "n", "n_attacks",
            "mean", "spread", "context", "equipment", "session", "date"), parsed[0])
        assertEquals(listOf("B♭ clarinet", "442.0", "D5", "C5", "38", "3", "4.3", "6.0",
            "drone", input.equipment, input.session, input.date), parsed[1])
        assertEquals(2, parsed.size)
        assertEquals(listOf(StatisticsCsv.header), parse(StatisticsCsv.encode(emptyList()).decodeToString()))
    }

    @Test fun `EXP-02 every stored statistic round-trips within a tenth of a cent in any locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val rows = (-500..500).map { value -> StatisticsRow("Horn", 415.0, "A4", "A4", 640, 3,
                value / 10.0 + 0.049, kotlin.math.abs(value / 10.0), "free", "", "", "2026-10-15") }
            val parsed = parse(StatisticsCsv.encode(rows).decodeToString()).drop(1)
            rows.zip(parsed).forEach { (row, fields) ->
                assertEquals(row.mean, fields[6].toDouble(), 0.051)
                assertEquals(row.spread, fields[7].toDouble(), 0.001)
                assertEquals(row.readings, fields[4].toLong())
                assertEquals(row.attacks, fields[5].toLong())
            }
        } finally { Locale.setDefault(previous) }
    }

    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            when {
                char == '"' && quoted && index < text.length && text[index] == '"' -> { field.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { fields.add(field.toString()); field.clear() }
                char == '\r' && !quoted -> {
                    assertEquals('\n', text[index++]); fields.add(field.toString()); field.clear()
                    rows.add(fields.toList()); fields.clear()
                }
                else -> field.append(char)
            }
        }
        assertFalse(quoted)
        assertTrue(field.isEmpty() && fields.isEmpty())
        return rows
    }
}
