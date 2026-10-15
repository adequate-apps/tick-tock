// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.config

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import ticktock.core.config.ParseResult
import ticktock.core.config.PersonalConfigCodec

/** Document access is bounded and completed before the player sees an import preview. */
object ConfigurationFiles {
    const val MAX_BYTES = 1024 * 1024

    fun read(context: Context, uri: Uri): ParseResult {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                check(out.size() + count <= MAX_BYTES) { "configuration exceeds the file limit" }
                out.write(block, 0, count)
            }
            out.toByteArray()
        } ?: error("document unavailable")
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return PersonalConfigCodec.parse(text)
    }

    fun write(context: Context, uri: Uri, bytes: ByteArray) {
        require(bytes.size <= MAX_BYTES)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("document unavailable")
    }
}
