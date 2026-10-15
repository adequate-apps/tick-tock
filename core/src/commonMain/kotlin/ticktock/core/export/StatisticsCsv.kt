// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.export

import ticktock.core.music.Cents
import kotlin.math.abs

/** One stored note summary with its capture labels (EXP-01). */
data class StatisticsRow(
    val instrument: String,
    val a4Hz: Double,
    val writtenNote: String,
    val concertNote: String,
    val readings: Long,
    val attacks: Long,
    val mean: Double,
    val spread: Double,
    val context: String,
    val equipment: String,
    val session: String,
    val date: String,
)

/** UTF-8, quoted fields and decimal points independent of the device locale. */
object StatisticsCsv {
    val header = listOf("instrument", "A4", "written note", "concert note", "n", "n_attacks",
        "mean", "spread", "context", "equipment", "session", "date")

    fun encode(rows: List<StatisticsRow>): ByteArray = buildString {
        append(header.joinToString(",")); append("\r\n")
        rows.forEach { row ->
            require(row.a4Hz.isFinite() && row.mean.isFinite() && row.spread.isFinite())
            require(row.readings >= 0 && row.attacks in 0..row.readings && row.spread >= 0)
            val fields = listOf(safeText(row.instrument), row.a4Hz.toString(), safeText(row.writtenNote), safeText(row.concertNote),
                row.readings.toString(), row.attacks.toString(), tenth(row.mean), tenth(row.spread),
                safeText(row.context), safeText(row.equipment), safeText(row.session), safeText(row.date))
            append(fields.joinToString(",", transform = ::quoted)); append("\r\n")
        }
    }.encodeToByteArray()

    private fun safeText(value: String): String =
        if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value

    private fun quoted(value: String): String = if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' })
        "\"${value.replace("\"", "\"\"")}\"" else value

    private fun tenth(value: Double): String {
        val units = requireNotNull(Cents.shown(value * 10))
        val magnitude = abs(units)
        return "${if (units < 0) "-" else ""}${magnitude / 10}.${magnitude % 10}"
    }
}
