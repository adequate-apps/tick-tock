// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppIdentityTest {
    @Test
    fun `the string resource and the constant agree`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(AppIdentity.NAME, context.getString(R.string.app_name))
    }

    @Test
    fun `the name literal appears in exactly one resource and one Kotlin file`() {
        // Working directory is the app module; scan every module's main sources.
        val root = File("..").canonicalFile
        val hits = listOf("app", "core", "engine", "data", "design")
            .map { File(root, "$it/src/main") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") } }
            .filter { it.readText().contains(AppIdentity.NAME) }
            .map { it.relativeTo(root).path }
            .sorted()
        assertEquals(
            listOf("app/src/main/kotlin/ticktock/app/AppIdentity.kt", "app/src/main/res/values/strings.xml"),
            hits,
        )
    }
}
