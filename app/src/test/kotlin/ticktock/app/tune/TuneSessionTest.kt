// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ticktock.core.contracts.fakes.FakeHistorySink
import ticktock.core.detect.ResponseMode
import ticktock.core.prefs.Prefs
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class TuneSessionTest {
    @Test
    fun `unrelated prefs queue no detector settings and changed settings queue only their field`() {
        val prefs = Prefs()
        val session = TuneSession(RuntimeEnvironment.getApplication(), prefs, FakeHistorySink())
        try {
            val capture = session.javaClass.getDeclaredField("capture").apply { isAccessible = true }.get(session)
            fun pending(name: String): Any? =
                (capture.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(capture) as AtomicReference<*>).getAndSet(null)
            session.settingsChanged(prefs.copy(capturePaused = true, earsFirst = true))
            assertNull(pending("requestedA4"))
            assertNull(pending("requestedMode"))
            val tuned = prefs.copy(a4Hz = 442.0)
            session.settingsChanged(tuned)
            assertEquals(442.0, pending("requestedA4"))
            assertNull(pending("requestedMode"))
            session.settingsChanged(tuned.copy(responseMode = ResponseMode.FAST))
            assertNull(pending("requestedA4"))
            assertEquals(ResponseMode.FAST, pending("requestedMode"))
        } finally {
            session.close()
        }
    }
}
