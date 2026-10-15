// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
    @Test
    fun `parses every value kind`() {
        val doc = Json.parse("""{"a": 1, "b": -2.5, "c": "x\ny\u0041", "d": [true, false, null], "e": {}}""").obj!!
        assertEquals(1, doc["a"]?.int)
        assertEquals(-2.5, doc["b"]?.number)
        assertNull(doc["b"]?.int)
        assertEquals("x\nyA", doc["c"]?.string)
        val d = doc["d"]?.array?.items!!
        assertEquals(true, d[0].bool)
        assertEquals(false, d[1].bool)
        assertEquals(JsonValue.JsonNull, d[2])
        assertEquals(emptySet<String>(), doc["e"]?.obj?.keys)
    }

    @Test
    fun `keeps object key order and writes it back the same way`() {
        val text = Json.write(Json.parse("""{"z": 1, "a": [1, 2], "m": {"k": "v"}, "n": 1.5, "q": "he said \"hi\""}"""))
        val expected = """
            {
              "z": 1,
              "a": [
                1,
                2
              ],
              "m": {
                "k": "v"
              },
              "n": 1.5,
              "q": "he said \"hi\""
            }

        """.trimIndent()
        assertEquals(expected, text)
        assertEquals(Json.parse(text), Json.parse(expected))
    }

    @Test
    fun `round trip is stable`() {
        val original = """{"format": "x", "version": 1, "list": [], "obj": {}, "t": true, "s": "\u00e9\t"}"""
        val once = Json.write(Json.parse(original))
        val twice = Json.write(Json.parse(once))
        assertEquals(once, twice)
    }

    @Test
    fun `rejects malformed documents with a located message`() {
        val cases = listOf(
            "{\"a\": 1} x" to "unexpected text",
            "{\"a\": 1, \"a\": 2}" to "duplicate key",
            "{\"a\": tru}" to "expected true",
            "[1, 2" to "unexpected end",
            "\"abc" to "unterminated string",
            "{\"a\": \"\\q\"}" to "unknown escape",
            "{a: 1}" to "expected a quoted key",
            "" to "unexpected end",
        )
        for ((text, fragment) in cases) {
            val error = assertThrows(JsonParseException::class.java) { Json.parse(text) }
            check(error.message!!.contains(fragment)) { "'$text' gave '${error.message}', expected '$fragment'" }
        }
    }

    @Test
    fun `int accessor only accepts whole numbers`() {
        assertEquals(42, Json.parse("42").int)
        assertEquals(-7, Json.parse("-7.0").int)
        assertNull(Json.parse("7.25").int)
        assertNull(Json.parse("\"7\"").int)
    }
}
