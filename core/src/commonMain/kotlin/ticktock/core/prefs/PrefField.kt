// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.prefs

import ticktock.core.catalogue.Catalogue
import ticktock.core.config.PortableSettings
import ticktock.core.json.Json
import ticktock.core.json.JsonValue
import ticktock.core.metro.Accent

/**
 * Ties a portable field to its slot in the personal configuration (owner
 * decision 3). A field with a link travels in the config file; one without
 * stays on the device.
 */
class PortableLink<T>(
    val read: (PortableSettings) -> T?,
    val write: (PortableSettings, T) -> PortableSettings,
)

/** A read value and, when the stored value had to be corrected, one sentence saying how. */
data class Normalised<T>(val value: T, val note: String?)

/** What a field made of a stored value. */
sealed class Decoded<out T> {
    data class Valid<T>(val value: T, val note: String? = null) : Decoded<T>()
    data object Invalid : Decoded<Nothing>()
}

/**
 * One row of the field table: the key in the prefs file, the default, how to
 * read a stored value leniently, how to write one, and where it lives in
 * [Prefs]. The read rule is the same for every row: a missing or null value
 * is the default and nothing is said; a value that is present and wrong is
 * the default and a note names the key, the value, the allowed values and
 * the default, so Diagnostics can show what was corrected (ERR-05 asks that
 * the player is never greeted as new because one field was off).
 */
abstract class PrefField<T>(
    val key: String,
    val default: T,
    val get: (Prefs) -> T,
    val set: (Prefs, T) -> Prefs,
    val portable: PortableLink<T>?,
) {
    /** The allowed values in a short phrase: "415 to 466", "one of fast, normal, slow". */
    abstract val allowed: String

    abstract fun encode(value: T): JsonValue

    protected abstract fun decode(raw: JsonValue): Decoded<T>

    fun normalise(raw: JsonValue?): Normalised<T> {
        if (raw == null || raw is JsonValue.JsonNull) return Normalised(default, null)
        return when (val decoded = decode(raw)) {
            is Decoded.Valid -> Normalised(decoded.value, decoded.note?.let { "$key: $it" })
            Decoded.Invalid -> Normalised(default, "$key: ${describe(raw)} is not $allowed; using ${describeDefault()}")
        }
    }

    fun exportTo(settings: PortableSettings, prefs: Prefs): PortableSettings =
        portable?.write(settings, get(prefs)) ?: settings

    fun importFrom(prefs: Prefs, settings: PortableSettings): Prefs =
        portable?.read(settings)?.let { set(prefs, it) } ?: prefs

    private fun describeDefault(): String = if (default == null) "nothing" else describe(encode(default))

    override fun toString() = "PrefField($key)"

    companion object {
        fun describe(value: JsonValue): String = when (value) {
            is JsonValue.JsonNull -> "null"
            is JsonValue.JsonString -> "\"${value.value}\""
            is JsonValue.JsonNumber, is JsonValue.JsonBool -> Json.write(value).trim()
            is JsonValue.JsonArray -> "a list"
            is JsonValue.JsonObject -> "an object"
        }
    }
}

class IntField(
    key: String,
    default: Int,
    val range: IntRange,
    get: (Prefs) -> Int,
    set: (Prefs, Int) -> Prefs,
    portable: PortableLink<Int>? = null,
) : PrefField<Int>(key, default, get, set, portable) {
    override val allowed = "${range.first} to ${range.last}"
    override fun encode(value: Int) = Json.num(value)
    override fun decode(raw: JsonValue): Decoded<Int> = raw.int?.takeIf { it in range }?.let { Decoded.Valid(it) } ?: Decoded.Invalid
}

/** An integer that may also be absent, for values the app measures or the player may leave unset. */
class OptionalIntField(
    key: String,
    val range: IntRange,
    get: (Prefs) -> Int?,
    set: (Prefs, Int?) -> Prefs,
) : PrefField<Int?>(key, null, get, set, null) {
    override val allowed = "${range.first} to ${range.last}, or nothing"
    override fun encode(value: Int?) = value?.let { Json.num(it) } ?: JsonValue.JsonNull
    override fun decode(raw: JsonValue): Decoded<Int?> = raw.int?.takeIf { it in range }?.let { Decoded.Valid(it) } ?: Decoded.Invalid
}

class DoubleField(
    key: String,
    default: Double,
    val range: ClosedFloatingPointRange<Double>,
    get: (Prefs) -> Double,
    set: (Prefs, Double) -> Prefs,
    portable: PortableLink<Double>? = null,
) : PrefField<Double>(key, default, get, set, portable) {
    override val allowed = "${trim(range.start)} to ${trim(range.endInclusive)}"
    override fun encode(value: Double) = Json.num(value)
    override fun decode(raw: JsonValue): Decoded<Double> = raw.number?.takeIf { it in range }?.let { Decoded.Valid(it) } ?: Decoded.Invalid

    private fun trim(value: Double) = if (value == kotlin.math.round(value)) value.toLong().toString() else value.toString()
}

/** A linear gain from 0 to 1; 0 is a value, never "unset" (SES-13). */
class GainField(
    key: String,
    default: Float,
    get: (Prefs) -> Float,
    set: (Prefs, Float) -> Prefs,
) : PrefField<Float>(key, default, get, set, null) {
    override val allowed = "0 to 1"
    override fun encode(value: Float) = Json.num(value.toDouble())
    override fun decode(raw: JsonValue): Decoded<Float> =
        raw.number?.takeIf { it in 0.0..1.0 }?.let { Decoded.Valid(it.toFloat()) } ?: Decoded.Invalid
}

class BoolField(
    key: String,
    default: Boolean,
    get: (Prefs) -> Boolean,
    set: (Prefs, Boolean) -> Prefs,
    portable: PortableLink<Boolean>? = null,
) : PrefField<Boolean>(key, default, get, set, portable) {
    override val allowed = "true or false"
    override fun encode(value: Boolean) = Json.bool(value)
    override fun decode(raw: JsonValue): Decoded<Boolean> = raw.bool?.let { Decoded.Valid(it) } ?: Decoded.Invalid
}

/** One of a fixed set of values, stored by its wire name. */
class ChoiceField<E : Any>(
    key: String,
    default: E,
    val entries: List<E>,
    val wire: (E) -> String,
    get: (Prefs) -> E,
    set: (Prefs, E) -> Prefs,
    portable: PortableLink<E>? = null,
) : PrefField<E>(key, default, get, set, portable) {
    override val allowed = "one of ${entries.joinToString { wire(it) }}"
    override fun encode(value: E) = Json.str(wire(value))
    override fun decode(raw: JsonValue): Decoded<E> {
        val text = raw.string ?: return Decoded.Invalid
        return entries.firstOrNull { wire(it) == text }?.let { Decoded.Valid(it) } ?: Decoded.Invalid
    }
}

/** One of a fixed set of whole numbers, such as the note value of a beat. */
class IntChoiceField(
    key: String,
    default: Int,
    val choices: List<Int>,
    get: (Prefs) -> Int,
    set: (Prefs, Int) -> Prefs,
) : PrefField<Int>(key, default, get, set, null) {
    override val allowed = "one of ${choices.joinToString()}"
    override fun encode(value: Int) = Json.num(value)
    override fun decode(raw: JsonValue): Decoded<Int> = raw.int?.takeIf { it in choices }?.let { Decoded.Valid(it) } ?: Decoded.Invalid
}

/** Free text the player typed: trimmed, and cut to [maxLength] with a note rather than refused. */
class TextField(
    key: String,
    default: String,
    val maxLength: Int,
    get: (Prefs) -> String,
    set: (Prefs, String) -> Prefs,
) : PrefField<String>(key, default, get, set, null) {
    override val allowed = "text up to $maxLength characters"
    override fun encode(value: String) = Json.str(value)
    override fun decode(raw: JsonValue): Decoded<String> {
        val text = raw.string?.trim() ?: return Decoded.Invalid
        if (text.length <= maxLength) return Decoded.Valid(text)
        return Decoded.Valid(text.take(maxLength), "${text.length} characters cut to $maxLength")
    }
}

class InstrumentField(
    key: String,
    get: (Prefs) -> String,
    set: (Prefs, String) -> Prefs,
    portable: PortableLink<String>?,
) : PrefField<String>(key, Catalogue.DEFAULT_KEY, get, set, portable) {
    override val allowed = "an instrument key"
    override fun encode(value: String) = Json.str(value)
    override fun decode(raw: JsonValue): Decoded<String> =
        raw.string?.takeIf { Catalogue.find(it) != null }?.let { Decoded.Valid(it) } ?: Decoded.Invalid
}

val Accent.wire: String
    get() = when (this) {
        Accent.ACCENT -> "accent"
        Accent.NORMAL -> "normal"
        Accent.SOFT -> "soft"
        Accent.MUTE -> "mute"
    }

/**
 * The accent row. An entry that is not an accent name becomes a plain beat
 * and is noted; the row's length against the beat count is a rule between
 * two fields, applied by [PrefsSchema.normalise].
 */
class AccentsField(
    key: String,
    get: (Prefs) -> List<Accent>,
    set: (Prefs, List<Accent>) -> Prefs,
) : PrefField<List<Accent>>(key, Prefs.defaultAccentRow(4), get, set, null) {
    override val allowed = "a list of ${Accent.entries.joinToString { it.wire }}"
    override fun encode(value: List<Accent>) = Json.arr(value.map { Json.str(it.wire) })
    override fun decode(raw: JsonValue): Decoded<List<Accent>> {
        val items = raw.array?.items ?: return Decoded.Invalid
        val bad = ArrayList<String>()
        val accents = items.map { item ->
            val match = item.string?.let { text -> Accent.entries.firstOrNull { it.wire == text } }
            if (match == null) bad.add(describe(item))
            match ?: Accent.NORMAL
        }
        val note = if (bad.isEmpty()) null else "${bad.joinToString()} ${if (bad.size == 1) "is" else "are"} not an accent; using normal"
        return Decoded.Valid(accents, note)
    }
}
