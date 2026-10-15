// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.json

/**
 * A small JSON model for :core. The module has no Android runtime and no
 * serialization library on its classpath, and the documents it handles (range
 * layers, the personal configuration) are a few kilobytes, so a strict,
 * allocation-happy tree parser is the right size. Objects keep insertion order,
 * which makes written output stable and diffs readable.
 */
sealed class JsonValue {
    data class JsonObject(val fields: LinkedHashMap<String, JsonValue> = LinkedHashMap()) : JsonValue() {
        operator fun get(key: String): JsonValue? = fields[key]
        operator fun set(key: String, value: JsonValue) {
            fields[key] = value
        }
        val keys: Set<String> get() = fields.keys
    }

    data class JsonArray(val items: List<JsonValue>) : JsonValue()
    data class JsonString(val value: String) : JsonValue()
    data class JsonNumber(val value: Double) : JsonValue()
    data class JsonBool(val value: Boolean) : JsonValue()
    object JsonNull : JsonValue() {
        override fun toString() = "JsonNull"
    }

    /** The string if this is one, else null. */
    val string: String? get() = (this as? JsonString)?.value

    /** The number if this is one, else null. */
    val number: Double? get() = (this as? JsonNumber)?.value

    /** The number as an Int when it is one exactly, else null. */
    val int: Int? get() = number?.takeIf { it == kotlin.math.round(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()

    val bool: Boolean? get() = (this as? JsonBool)?.value
    val obj: JsonObject? get() = this as? JsonObject
    val array: JsonArray? get() = this as? JsonArray
}

class JsonParseException(message: String) : RuntimeException(message)

object Json {
    fun parse(text: String, maxDepth: Int = 64): JsonValue {
        require(maxDepth in 1..64)
        val parser = Parser(text, maxDepth)
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw parser.error("unexpected text after the document")
        return value
    }

    /** Pretty JSON with two-space indentation, object keys in insertion order, and a trailing newline. */
    fun write(value: JsonValue): String = StringBuilder().also { write(value, it, 0) }.append('\n').toString()

    fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.JsonObject =
        JsonValue.JsonObject(LinkedHashMap<String, JsonValue>().apply { pairs.forEach { (k, v) -> put(k, v) } })

    fun str(value: String) = JsonValue.JsonString(value)
    fun num(value: Int) = JsonValue.JsonNumber(value.toDouble())
    fun num(value: Double) = JsonValue.JsonNumber(value)
    fun bool(value: Boolean) = JsonValue.JsonBool(value)
    fun arr(items: List<JsonValue>) = JsonValue.JsonArray(items)

    private fun write(value: JsonValue, out: StringBuilder, depth: Int) {
        when (value) {
            is JsonValue.JsonNull -> out.append("null")
            is JsonValue.JsonBool -> out.append(value.value)
            is JsonValue.JsonNumber -> out.append(formatNumber(value.value))
            is JsonValue.JsonString -> writeString(value.value, out)
            is JsonValue.JsonArray -> {
                if (value.items.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                value.items.forEachIndexed { index, item ->
                    indent(out, depth + 1)
                    write(item, out, depth + 1)
                    if (index < value.items.lastIndex) out.append(',')
                    out.append('\n')
                }
                indent(out, depth)
                out.append(']')
            }
            is JsonValue.JsonObject -> {
                if (value.fields.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                val entries = value.fields.entries.toList()
                entries.forEachIndexed { index, (key, item) ->
                    indent(out, depth + 1)
                    writeString(key, out)
                    out.append(": ")
                    write(item, out, depth + 1)
                    if (index < entries.lastIndex) out.append(',')
                    out.append('\n')
                }
                indent(out, depth)
                out.append('}')
            }
        }
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) throw IllegalArgumentException("JSON has no representation for $value")
        return if (value == kotlin.math.round(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
    }

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append("  ") }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') out.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else out.append(ch)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String, private val maxDepth: Int) {
        private var pos = 0

        fun atEnd() = pos >= text.length

        fun error(message: String) = JsonParseException("JSON error at offset $pos: $message")

        fun skipWhitespace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
        }

        fun readValue(depth: Int = 0): JsonValue {
            if (depth > maxDepth) throw error("nesting exceeds the document limit")
            skipWhitespace()
            if (atEnd()) throw error("unexpected end of document")
            return when (val ch = text[pos]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> JsonValue.JsonString(readString())
                't' -> literal("true", JsonValue.JsonBool(true))
                'f' -> literal("false", JsonValue.JsonBool(false))
                'n' -> literal("null", JsonValue.JsonNull)
                else -> if (ch == '-' || ch in '0'..'9') readNumber() else throw error("unexpected character '$ch'")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, pos)) throw error("expected $word")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): JsonValue.JsonObject {
            pos++ // {
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.JsonObject(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw error("expected a quoted key")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') throw error("expected ':' after key \"$key\"")
                pos++
                val value = readValue(depth)
                if (fields.containsKey(key)) throw error("duplicate key \"$key\"")
                fields[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.JsonObject(fields)
                    }
                    else -> throw error("expected ',' or '}' in object")
                }
            }
        }

        private fun readArray(depth: Int): JsonValue.JsonArray {
            pos++ // [
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.JsonArray(items)
            }
            while (true) {
                items.add(readValue(depth))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.JsonArray(items)
                    }
                    else -> throw error("expected ',' or ']' in array")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw error("unterminated string")
                val ch = text[pos++]
                when {
                    ch == '"' -> return out.toString()
                    ch == '\\' -> {
                        if (atEnd()) throw error("unterminated escape")
                        when (val esc = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw error("short \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: throw error("bad \\u escape '$hex'")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> throw error("unknown escape '\\$esc'")
                        }
                    }
                    ch < ' ' -> throw error("control character in string")
                    else -> out.append(ch)
                }
            }
        }

        private fun readNumber(): JsonValue.JsonNumber {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && (text[pos] in '0'..'9' || text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')) pos++
            val slice = text.substring(start, pos)
            val value = slice.toDoubleOrNull() ?: throw error("bad number '$slice'")
            if (!value.isFinite()) throw error("number exceeds the supported range")
            return JsonValue.JsonNumber(value)
        }

        private fun peek(): Char = if (atEnd()) throw error("unexpected end of document") else text[pos]
    }
}
