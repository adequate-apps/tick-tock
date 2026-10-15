// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import ticktock.app.AppGraph

/** Where the microphone permission stands. */
enum class MicState {
    ALLOWED,

    /** Never asked: the first tap asks. */
    NOT_ASKED,

    /** Refused, and Android will still ask again. */
    DENIED,

    /** A request was denied without a rationale this session; Android settings are the next action. */
    DENIED_ALWAYS,
}

/** The permission's state and the two ways to change it. */
class MicAccess(val state: MicState, val request: () -> Unit, val openSettings: () -> Unit)

/**
 * The microphone permission as the Tune pane and the status bar see it. The
 * state is read again whenever the activity resumes, so a permission granted
 * in Android's settings shows the moment the player comes back; a grant
 * starts listening at once.
 */
@Composable
fun rememberMicAccess(graph: AppGraph): MicAccess {
    val activity = LocalActivity.current
    var deniedInSession by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(micState(activity, graph)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        graph.prefs.update { it.copy(micAsked = true) }
        deniedInSession = !granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
        state = if (granted) MicState.ALLOWED else micState(activity, graph, asked = true, deniedInSession = deniedInSession)
        if (granted) graph.tune.resume()
    }
    LifecycleResumeEffect(Unit) {
        state = micState(activity, graph, deniedInSession = deniedInSession)
        if (state == MicState.ALLOWED) deniedInSession = false
        onPauseOrDispose { }
    }
    return MicAccess(
        state = state,
        request = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
        openSettings = {
            activity?.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
            )
        },
    )
}

private fun micState(
    activity: Activity?,
    graph: AppGraph,
    asked: Boolean = graph.prefs.prefs.value.micAsked,
    deniedInSession: Boolean = false,
): MicState {
    if (activity == null) return MicState.NOT_ASKED
    return when {
        graph.tune.micAllowed() ||
            activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> MicState.ALLOWED
        !asked -> MicState.NOT_ASKED
        activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> MicState.DENIED
        deniedInSession -> MicState.DENIED_ALWAYS
        // A saved request can precede a system revoke or auto-reset, when Android can ask again.
        else -> MicState.DENIED
    }
}
