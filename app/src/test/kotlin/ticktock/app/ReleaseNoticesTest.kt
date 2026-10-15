// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReleaseNoticesTest {
    @Test fun `bundled notices include the complete font licence`() {
        val notices = RuntimeEnvironment.getApplication().assets.open("THIRD_PARTY.md").bufferedReader().use { it.readText() }
        assertTrue(notices.contains("SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007"))
        assertTrue(notices.contains("PERMISSION & CONDITIONS"))
        assertTrue(notices.contains("TERMINATION"))
        assertTrue(notices.contains("OTHER DEALINGS IN THE FONT SOFTWARE."))
    }
}
