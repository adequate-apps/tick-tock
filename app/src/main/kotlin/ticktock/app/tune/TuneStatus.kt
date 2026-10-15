// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ticktock.app.R
import ticktock.app.audio.CaptureState
import ticktock.core.music.Pitch
import ticktock.core.prefs.Prefs

/** Which microphone fact the status bar carries, if any (foundations, Status bar). */
enum class MicWord { LISTENING, PAUSED, MIC_OFF, MIC_DENIED, MIC_BUSY }

fun micWord(mic: MicState, capture: CaptureState, prefs: Prefs): MicWord? = when (gateOf(mic, capture)) {
    Gate.MIC_NOT_ASKED -> MicWord.MIC_OFF
    Gate.MIC_DENIED, Gate.MIC_DENIED_ALWAYS -> MicWord.MIC_DENIED
    Gate.MIC_BUSY -> MicWord.MIC_BUSY
    Gate.NO_INPUT -> MicWord.MIC_OFF
    null -> when {
        !capture.isListening -> null
        prefs.capturePaused -> MicWord.PAUSED
        else -> MicWord.LISTENING
    }
}

/** The status segments: the microphone's word and A4 when it is off 440, one or two words each and only while true. */
@Composable
fun statusWords(word: MicWord?, a4Hz: Double): List<String> = buildList {
    when (word) {
        MicWord.LISTENING -> add(stringResource(R.string.status_listening))
        MicWord.PAUSED -> add(stringResource(R.string.status_paused))
        MicWord.MIC_OFF -> add(stringResource(R.string.status_mic_off))
        MicWord.MIC_DENIED -> add(stringResource(R.string.status_mic_denied))
        MicWord.MIC_BUSY -> add(stringResource(R.string.status_mic_busy))
        null -> Unit
    }
    // A4 shows whenever it is off 440, so a borrowed phone never tunes to a stranger's reference unnoticed.
    if (a4Hz != Pitch.A4_DEFAULT_HZ) add(stringResource(R.string.status_a4, formatA4(a4Hz)))
}
