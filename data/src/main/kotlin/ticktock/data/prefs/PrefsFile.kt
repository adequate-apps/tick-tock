// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.prefs

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** The bytes behind the prefs, so tests can make reads and writes fail the way a full or broken disk would. */
interface PrefsFile {
    /** The stored text, or null when nothing has been saved yet. Throws when storage cannot be read (ERR-05). */
    fun read(): String?

    /** Replaces the stored text in one step: a crash mid-write leaves the old text (AtomicFile). */
    fun write(text: String)

    /** Keeps the current bytes under [name] beside the file, before anything overwrites them (ERR-04). */
    fun copyAside(name: String)
}

class AtomicPrefsFile(private val file: File) : PrefsFile {
    private val atomic = AtomicFile(file)

    override fun read(): String? = try {
        atomic.readFully().decodeToString()
    } catch (missing: FileNotFoundException) {
        null
    }

    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val out = atomic.startWrite()
        try {
            out.write(text.encodeToByteArray())
            atomic.finishWrite(out)
        } catch (failure: Exception) {
            atomic.failWrite(out)
            throw failure
        }
    }

    override fun copyAside(name: String) {
        file.copyTo(File(file.parentFile, name), overwrite = false)
    }
}
