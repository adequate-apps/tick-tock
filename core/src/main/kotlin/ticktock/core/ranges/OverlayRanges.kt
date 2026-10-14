// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.ranges

import ticktock.core.json.Json
import ticktock.core.json.JsonValue

/** One curated correction, in written MIDI, with the reason it exists. */
data class OverlayEntry(val range: WrittenRange, val reason: String)

/**
 * The app overlay layer (owner decision 3, layer 2): versioned, every entry with
 * a reason. A keyed entry replaces the base keyed tier; an extended entry
 * replaces the base extended tier. [decisions] records why keys with a wide
 * professional top keep it (RNG-05).
 */
class OverlayRanges(
    val version: Int,
    val keyed: Map<String, OverlayEntry>,
    val extended: Map<String, OverlayEntry>,
    val decisions: Map<String, String>,
    /** sha256 of the source document the generator read; empty when the file names none. */
    val sourceSha256: String = "",
) {
    companion object {
        const val SCHEMA = "ticktock-range-overlay"
        const val RESOURCE = "/ranges/overlay.json"

        val bundled: OverlayRanges by lazy { parse(BaseRanges.readResource(RESOURCE)) }

        fun parse(text: String): OverlayRanges {
            val root = Json.parse(text).obj ?: throw RangeDataException("overlay is not an object")
            val schema = root["schema"]?.string
            if (schema != SCHEMA) throw RangeDataException("overlay schema is \"$schema\", expected \"$SCHEMA\"")
            val version = root["version"]?.int ?: throw RangeDataException("overlay has no version")
            if (version != 1) throw RangeDataException("overlay version $version is unknown; this build reads version 1")
            val decisions = LinkedHashMap<String, String>()
            root["decisions"]?.obj?.fields?.forEach { (key, value) ->
                decisions[key] = value.string?.takeIf { it.isNotBlank() }
                    ?: throw RangeDataException("decisions.$key: a decision needs its reason")
            }
            val sha = root["sourceSha256"]?.string ?: ""
            return OverlayRanges(version, entries(root["keyed"], "keyed"), entries(root["extended"], "extended"), decisions, sha)
        }

        private fun entries(value: JsonValue?, tier: String): Map<String, OverlayEntry> {
            val result = LinkedHashMap<String, OverlayEntry>()
            val fields = value?.obj?.fields ?: return result
            for ((key, raw) in fields) {
                val entry = raw.obj ?: throw RangeDataException("$tier.$key: entry is not an object")
                val low = entry["low"]?.int ?: throw RangeDataException("$tier.$key: low is not an integer")
                val high = entry["high"]?.int ?: throw RangeDataException("$tier.$key: high is not an integer")
                if (low > high) throw RangeDataException("$tier.$key: runs backwards")
                val reason = entry["reason"]?.string?.takeIf { it.isNotBlank() }
                    ?: throw RangeDataException("$tier.$key: every overlay entry needs a reason")
                result[key] = OverlayEntry(WrittenRange(low, high), reason)
            }
            return result
        }
    }
}
