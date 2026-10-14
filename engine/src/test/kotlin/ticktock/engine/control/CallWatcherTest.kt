// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import ticktock.engine.control.fakes.FakeAudioModes
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class CallWatcherTest {
    private val handler = Handler(Looper.getMainLooper())
    private val modes = FakeAudioModes()
    private val seen = mutableListOf<CallState>()

    private fun idleFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun `CAL-03 on API 28 to 30 the mode is polled at 2 Hz and a change is seen within a second`() {
        val watcher = CallWatcher(modes, handler, apiLevel = 28) { seen += it }
        watcher.start()
        assertFalse(modes.listening)
        val readsAtStart = modes.reads

        idleFor(2_000)
        assertEquals(4, modes.reads - readsAtStart)

        modes.setMode(AudioManager.MODE_RINGTONE)
        idleFor(CallWatcher.POLL_MILLIS)
        assertEquals(listOf(CallState.RINGING), seen)

        modes.setMode(AudioManager.MODE_NORMAL)
        idleFor(CallWatcher.POLL_MILLIS)
        assertEquals(listOf(CallState.RINGING, CallState.NONE), seen)

        watcher.stop()
        val readsAtStop = modes.reads
        idleFor(2_000)
        assertEquals(readsAtStop, modes.reads)
    }

    @Test
    fun `CAL-03 on API 31 and later the mode-changed listener is used and nothing polls`() {
        val watcher = CallWatcher(modes, handler, apiLevel = 31) { seen += it }
        watcher.start()
        assertTrue(modes.listening)
        assertEquals(1, modes.listenCalls)
        val readsAtStart = modes.reads

        idleFor(5_000)
        assertEquals(readsAtStart, modes.reads)

        modes.setMode(AudioManager.MODE_RINGTONE)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(CallState.RINGING), seen)

        watcher.stop()
        assertFalse(modes.listening)
    }

    @Test
    fun `a repeated mode and a mode of no interest are not changes`() {
        val watcher = CallWatcher(modes, handler, apiLevel = 31) { seen += it }
        watcher.start()
        modes.setMode(AudioManager.MODE_NORMAL)
        modes.setMode(AudioManager.MODE_IN_CALL)
        modes.setMode(AudioManager.MODE_IN_COMMUNICATION)
        modes.setMode(AudioManager.MODE_NORMAL)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(CallState.ANSWERED, CallState.NONE), seen)
    }

    @Test
    fun `CAL-02 ringtone is ringing, every in-call mode is answered, the rest is nothing`() {
        assertEquals(CallState.RINGING, CallWatcher.stateOf(AudioManager.MODE_RINGTONE))
        assertEquals(CallState.ANSWERED, CallWatcher.stateOf(AudioManager.MODE_IN_CALL))
        assertEquals(CallState.ANSWERED, CallWatcher.stateOf(AudioManager.MODE_IN_COMMUNICATION))
        assertEquals(CallState.ANSWERED, CallWatcher.stateOf(AudioManager.MODE_CALL_SCREENING))
        assertEquals(CallState.NONE, CallWatcher.stateOf(AudioManager.MODE_NORMAL))
        assertEquals(CallState.NONE, CallWatcher.stateOf(AudioManager.MODE_INVALID))
        assertEquals(CallState.NONE, CallWatcher.stateOf(AudioManager.MODE_CURRENT))
    }
}
