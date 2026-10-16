// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The JNI symbols in the one bridge file and the external declarations here
 * are two lists of the same names; this keeps them equal, and keeps the
 * bridge the only file that speaks JNI.
 */
class NativeBridgeTest {
    private val cppDir = File("src/main/cpp")

    /** Plain Java reflection: the native modifier marks every external fun. */
    private fun nativeMethods(): Set<String> =
        NativeBridge::class.java.declaredMethods.filter { Modifier.isNative(it.modifiers) }.map { it.name }.toSet()

    private fun jniSymbols(file: File): Set<String> =
        Regex("""Java_ticktock_engine_NativeBridge_(\w+)\(""").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `every external function has its JNI symbol and nothing else is exported`() {
        val externals = nativeMethods()
        val symbols = jniSymbols(File(cppDir, "JniBridge.cpp"))
        assertEquals(externals, symbols)
        assertTrue(externals.size >= 18)
    }

    @Test
    fun `the bridge is the only file that includes jni`() {
        val jniFiles = cppDir.walkTopDown()
            .filter { it.isFile && (it.extension == "cpp" || it.extension == "h") }
            .filter { "#include <jni.h>" in it.readText() }
            .map { it.name }
            .toList()
        assertEquals(listOf("JniBridge.cpp"), jniFiles)
    }

    @Test
    fun `every OutputCommand variant has a native method`() {
        val externals = nativeMethods()
        val expected = listOf(
            "nativeRegister", "nativeUnregister", "nativeVoiceOn", "nativeVoiceOff", "nativeVoiceGain",
            "nativeSetA4", "nativeDuck", "nativeMetroStart", "nativeMetroUpdate", "nativeMetroStop", "nativeMute",
        )
        assertEquals(emptyList<String>(), expected.filter { it !in externals })
    }

    @Test
    fun `the event kinds in the bridge follow OutputEvent's declaration order`() {
        val header = File(cppDir, "control/OutputController.h").readText()
        val kinds = Regex("""enum class EventKind[^{]*\{([^}]*)\}""").find(header)!!.groupValues[1]
        val names = Regex("""(\w+) = (\d+)""").findAll(kinds).map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()
        assertEquals(
            listOf("Opened" to 0, "Closed" to 1, "Beat" to 2, "Parked" to 3, "Resumed" to 4, "Failed" to 5, "Counters" to 6, "DuckSettled" to 7, "RoutingChanged" to 8),
            names,
        )
    }
}
