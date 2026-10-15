// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.history

import kotlin.math.roundToInt

/**
 * The running summary of one note in one run: what `note_hist` stores and the
 * table reads after the raw readings age out (SES-08, SES-09). Attacks are
 * counted apart from readings, because one held note is many readings and one
 * attack (ATK-02).
 */
class NoteAggregate(
    var n: Long = 0,
    var sumCents: Double = 0.0,
    var sumSq: Double = 0.0,
    val bins: IntArray = IntArray(BIN_COUNT),
    var attacks: Long = 0,
    var sumAttackCents: Double = 0.0,
    var sumAttackSq: Double = 0.0,
) {
    fun add(cents: Double, firstSettled: Boolean) {
        n++
        sumCents += cents
        sumSq += cents * cents
        val bin = (cents.roundToInt() + BIN_OFFSET).coerceIn(0, BIN_COUNT - 1)
        if (bins[bin] < U16_MAX) bins[bin]++
        if (firstSettled) {
            attacks++
            sumAttackCents += cents
            sumAttackSq += cents * cents
        }
    }

    val hasAttacks: Boolean get() = attacks > 0

    fun copy(): NoteAggregate =
        NoteAggregate(n, sumCents, sumSq, bins.copyOf(), attacks, sumAttackCents, sumAttackSq)

    /** The histogram as the stored blob: one little-endian u16 per whole cent from -50 to +50. */
    fun binsBlob(): ByteArray = ByteArray(BIN_COUNT * 2).also { out ->
        bins.forEachIndexed { i, count ->
            out[2 * i] = (count and 0xFF).toByte()
            out[2 * i + 1] = (count ushr 8 and 0xFF).toByte()
        }
    }

    companion object {
        /** One bin per whole cent over -50..+50 (PLAN 3.4). */
        const val BIN_COUNT = 101
        private const val BIN_OFFSET = BIN_COUNT / 2

        /** A bin saturates rather than wrapping: a count that large is already off the chart. */
        private const val U16_MAX = 0xFFFF

        fun binsFrom(blob: ByteArray): IntArray = IntArray(BIN_COUNT) { i ->
            if (2 * i + 1 < blob.size) (blob[2 * i].toInt() and 0xFF) or ((blob[2 * i + 1].toInt() and 0xFF) shl 8) else 0
        }
    }
}
