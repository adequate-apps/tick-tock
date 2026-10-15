// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import ticktock.design.GridlineType
import ticktock.design.components.GlText

/** Freeze each announcement so a held note's tuning movement cannot speak at hop rate. */
@Composable
internal fun SettledAnnouncement(midi: Int?, message: String?, enabled: Boolean) {
    var announced by remember { mutableStateOf<String?>(null) }
    var previous by remember { mutableStateOf<Int?>(null) }
    val latest by rememberUpdatedState(message)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(midi, enabled, message != null, lifecycle) {
        if (!enabled || midi == null || message == null) {
            announced = null
            previous = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (previous == null) delay(1_000)
            if (previous != midi) {
                announced = latest
                previous = midi
            }
        }
    }
    if (enabled) announced?.let {
        GlText(it, style = GridlineType.small,
            modifier = Modifier.testTag("settled-announcement").semantics { liveRegion = LiveRegionMode.Polite })
    }
}
