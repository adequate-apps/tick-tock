// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * :core must stay a pure JVM module so the detector tests run in seconds for
 * the life of the project. The kotlin.jvm plugin already makes an Android
 * import a compile error; this test is the written-down reason, and it also
 * catches a stray Android artifact sneaking onto the test classpath.
 */
class ModuleWallTest {
    private val forbiddenImportPrefixes = listOf(
        "import android.",
        "import androidx.",
        "import dalvik.",
        "import com.android.",
    )

    @Test
    fun `core sources import nothing from the Android SDK`() {
        val roots = listOf("src/main/kotlin", "src/testFixtures/kotlin", "src/test/kotlin").map(::File)
        assertTrue("test must run with the module directory as working directory", roots.first().isDirectory)

        val offenders = roots.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val trimmed = line.trim()
                    if (forbiddenImportPrefixes.any(trimmed::startsWith)) "${file.path}:${index + 1}: $trimmed" else null
                }
            }
        assertEquals("Android imports in :core", emptyList<String>(), offenders)
    }

    @Test
    fun `core test classpath carries no Android runtime`() {
        val entries = System.getProperty("java.class.path").split(File.pathSeparator)
        val androidJars = entries.filter { path ->
            val name = File(path).name.lowercase()
            name.startsWith("android-") || name.startsWith("android.jar") || name.contains("robolectric")
        }
        assertEquals("Android jars on the :core classpath", emptyList<String>(), androidJars)
    }
}
