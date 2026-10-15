// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.history

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ShareFilesTest {
    @Test fun `EXP-01 CSV shares exact bytes with one temporary read grant from cache only`() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = "instrument,mean\r\nB♭ clarinet,-4.2\r\n".toByteArray(Charsets.UTF_8)
        val intent = ShareFiles.csv(context, bytes)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/csv", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
        assertTrue(uri.path!!.startsWith("/shared/intonation-"))
        assertTrue(uri.path!!.endsWith(".csv"))
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
        assertNotEquals(uri, ShareFiles.csv(context, bytes).clipData!!.getItemAt(0).uri)
        @Suppress("DEPRECATION") val provider = context.packageManager.resolveContentProvider(uri.authority!!, PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        for (file in listOf(File(context.filesDir, "prefs.json"), File(context.cacheDir, "private.csv"))) {
            try { FileProvider.getUriForFile(context, uri.authority!!, file); fail("private file was exposed") }
            catch (expected: IllegalArgumentException) { }
        }
    }
}
