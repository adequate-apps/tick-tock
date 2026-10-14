// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class EngineLibraryTest {
    @Test
    fun `kotlin and native ABI versions agree`() {
        val source = File("src/main/cpp/engine.cpp").readText()
        val native = Regex("""kEngineAbiVersion\s*=\s*(\d+)""").find(source)?.groupValues?.get(1)?.toInt()
        assertEquals(EngineLibrary.ABI_VERSION, native)
    }

    @Test
    fun `the CMake target name matches the library name`() {
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()
        assertEquals(true, Regex("""add_library\(${EngineLibrary.NAME}\s+SHARED""").containsMatchIn(cmake))
    }
}
