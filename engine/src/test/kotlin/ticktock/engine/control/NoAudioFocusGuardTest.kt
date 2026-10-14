// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.control

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * CAL-04 and decision 4: another app's playback is never a reason to stop,
 * duck or change gain. The control thread has no path through which it could
 * hear about one, which this guard keeps true: no focus request, no focus
 * listener, no playback callback and no "is music active" query anywhere in
 * the engine's production sources.
 */
class NoAudioFocusGuardTest {
    @Test
    fun `CAL-04 the engine never asks for audio focus or listens to other apps' playback`() {
        val forbidden = listOf(
            "requestAudioFocus",
            "abandonAudioFocus",
            "AudioFocusRequest",
            "OnAudioFocusChangeListener",
            "AUDIOFOCUS_",
            "AudioPlaybackCallback",
            "isMusicActive",
        )
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        check(sources.isNotEmpty()) { "no engine sources found; run from the module directory" }

        val hits = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                .filter { (_, line) -> forbidden.any { it in line } }
                .map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
        }
        assertEquals(emptyList<String>(), hits)
    }
}
