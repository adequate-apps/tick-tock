// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.ranges

import ticktock.core.json.Json
import ticktock.core.json.JsonValue

/**
 * The base layer as `tools/gen-ranges/gen_ranges.py` wrote it: MuseScore's
 * amateur and professional spans in sounding MIDI, plus the chromatic
 * transposition the MuseScore entry declares. Nothing here is interpreted; the
 * resolver converts to written pitch with the catalogue's transposition.
 */
data class BaseEntry(
    val musescoreId: String,
    val transposeChromatic: Int,
    val soundingKeyedLow: Int,
    val soundingKeyedHigh: Int,
    val soundingExtendedLow: Int,
    val soundingExtendedHigh: Int,
)

class BaseRanges(val version: Int, val sourceSha256: String, val entries: Map<String, BaseEntry>) {
    companion object {
        const val SCHEMA = "ticktock-range-base"
        const val RESOURCE = "/ranges/base.json"

        /** The committed base layer bundled with :core. */
        val bundled: BaseRanges by lazy { parse(readResource(RESOURCE)) }

        fun parse(text: String): BaseRanges {
            val root = Json.parse(text).obj ?: throw RangeDataException("base layer is not an object")
            val schema = root["schema"]?.string
            if (schema != SCHEMA) throw RangeDataException("base layer schema is \"$schema\", expected \"$SCHEMA\"")
            val version = root["version"]?.int ?: throw RangeDataException("base layer has no version")
            if (version != 1) throw RangeDataException("base layer version $version is unknown; this build reads version 1")
            val sha = root["sourceSha256"]?.string ?: ""
            val instruments = root["instruments"]?.obj ?: throw RangeDataException("base layer has no instruments")
            val entries = LinkedHashMap<String, BaseEntry>()
            for ((key, value) in instruments.fields) {
                val entry = value.obj ?: throw RangeDataException("$key: entry is not an object")
                val keyed = span(entry["soundingKeyed"], "$key.soundingKeyed")
                val extended = span(entry["soundingExtended"], "$key.soundingExtended")
                entries[key] = BaseEntry(
                    musescoreId = entry["musescoreId"]?.string ?: throw RangeDataException("$key: no musescoreId"),
                    transposeChromatic = entry["transposeChromatic"]?.int ?: throw RangeDataException("$key: no transposeChromatic"),
                    soundingKeyedLow = keyed.first,
                    soundingKeyedHigh = keyed.second,
                    soundingExtendedLow = extended.first,
                    soundingExtendedHigh = extended.second,
                )
            }
            return BaseRanges(version, sha, entries)
        }

        private fun span(value: JsonValue?, path: String): Pair<Int, Int> {
            val items = value?.array?.items ?: throw RangeDataException("$path: expected [low, high]")
            if (items.size != 2) throw RangeDataException("$path: expected two values")
            val low = items[0].int ?: throw RangeDataException("$path: low is not an integer")
            val high = items[1].int ?: throw RangeDataException("$path: high is not an integer")
            if (low > high) throw RangeDataException("$path: runs backwards")
            return low to high
        }

        internal fun readResource(path: String): String =
            BaseRanges::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
                ?: throw RangeDataException("resource $path is missing from :core")
    }
}

class RangeDataException(message: String) : RuntimeException(message)
