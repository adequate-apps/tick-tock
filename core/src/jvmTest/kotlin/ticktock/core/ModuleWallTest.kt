// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * :core must stay free of the Android SDK so the detector tests run in seconds
 * for the life of the project, and its commonMain must stay free of the JDK so
 * the same code compiles for iOS later (decision 18). No Android SDK is on the
 * classpath, so an Android import is already a compile error; this test is the
 * written-down reason, and it also catches a stray Android artifact sneaking
 * onto the test classpath.
 *
 * The JDK rule needs this test. With the JVM as the only target, the compiler
 * resolves commonMain against the JDK, so java.* compiles there until the iOS
 * target exists.
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
        val roots = listOf("src/commonMain/kotlin", "src/jvmMain/kotlin", "src/jvmTest/kotlin", "../core-testing/src/main/kotlin").map(::File)
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

    @Test
    fun `commonMain uses no JDK API`() {
        val root = File("src/commonMain/kotlin")
        assertTrue("test must run with the module directory as working directory", root.isDirectory)

        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//")
                    val hit = jdkPatterns.firstOrNull { it.containsMatchIn(code) }
                    if (hit != null) "${file.path}:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertEquals("JDK API in commonMain", emptyList<String>(), offenders)
    }

    @Test
    fun `the JDK guard catches the shapes it is meant to`() {
        val planted = listOf(
            "import java.io.File",
            "val t = System.nanoTime()",
            "Math.floorMod(a, 12)",
            "String.format(\"%d\", 1)",
            "synchronized(this) { n++ }",
            "Foo::class.java.getResource(path)",
            "val x = java.util.Locale.ROOT",
            "Thread.sleep(1)",
        )
        val missed = planted.filter { line -> jdkPatterns.none { it.containsMatchIn(line) } }
        assertEquals("planted JDK calls the guard missed", emptyList<String>(), missed)
        val clean = listOf("import kotlin.jvm.Synchronized", "val m = a.mod(12)", "x.copyInto(y)", "// java.io is fine in a comment")
            .map { it.substringBefore("//") }
        assertEquals("clean lines flagged", emptyList<String>(), clean.filter { line -> jdkPatterns.any { it.containsMatchIn(line) } })
    }

    private val jdkPatterns = listOf(
        Regex("""\b(java|javax|sun)\.[a-z]"""),
        Regex("""\bSystem\."""),
        Regex("""\bMath\."""),
        Regex("""\bString\.format\b"""),
        Regex("""\bsynchronized\s*\("""),
        Regex("""::class\.java\b|\.javaClass\b"""),
        Regex("""\bThread\b"""),
    )
}
