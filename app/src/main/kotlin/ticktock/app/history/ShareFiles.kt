// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.history

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Only a generated cache file is granted to the chosen receiving application. */
object ShareFiles {
    fun csv(context: Context, bytes: ByteArray): Intent {
        val directory = File(context.cacheDir, "shared")
        check(directory.isDirectory || directory.mkdirs())
        val pending = File.createTempFile("intonation-", ".pending", directory)
        pending.writeBytes(bytes)
        val file = File(directory, pending.name.removeSuffix(".pending") + ".csv")
        check(pending.renameTo(file))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Intonation CSV", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
