// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** Query the current reading on accessibility focus without publishing per-hop property changes. */
internal class NeedleAccessibilityView(context: Context) : View(context) {
    var reading: () -> String = { "—" }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        tag = "needle-accessibility"
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        AccessibilityNodeInfoCompat.wrap(info).stateDescription = reading()
    }
}

@Composable
internal fun NeedleAccessibility(label: String, spoken: String, modifier: Modifier) {
    val latest = rememberUpdatedState(spoken)
    AndroidView(modifier = modifier, factory = { context ->
        NeedleAccessibilityView(context).apply { reading = { latest.value }; contentDescription = label }
    }, update = { if (it.contentDescription != label) it.contentDescription = label })
}
