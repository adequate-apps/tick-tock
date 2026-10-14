// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageTest {
    @Test
    fun `storage file names are distinct`() {
        val names = listOf(Storage.DATABASE_FILE, Storage.PREFS_FILE, Storage.CRASH_LOG_FILE)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `crash log cap is 256 KiB`() {
        assertEquals(262_144, Storage.CRASH_LOG_MAX_BYTES)
    }
}
