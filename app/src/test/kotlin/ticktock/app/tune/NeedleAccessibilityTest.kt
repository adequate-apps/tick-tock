// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.tune

import android.app.Activity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NeedleAccessibilityTest {
    @Test fun `focus reads the latest note while held-focus changes emit no accessibility events`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val manager = activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        val events = mutableListOf<Int>()
        val root = object : FrameLayout(activity) {
            override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
                events += event.eventType
                return true
            }
        }
        val needle = NeedleAccessibilityView(activity)
        needle.contentDescription = "Needle"
        var spoken = "B flat 4, 6 cents sharp"
        needle.reading = { spoken }
        root.addView(needle, FrameLayout.LayoutParams(300, 120))
        activity.setContentView(root)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        fun state() = AccessibilityNodeInfoCompat.wrap(needle.createAccessibilityNodeInfo()).stateDescription
        assertEquals(spoken, state())
        assertTrue(needle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        assertTrue(events.contains(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
        events.clear()
        repeat(20) { spoken = "B flat 4, $it cents sharp"; shadowOf(android.os.Looper.getMainLooper()).idle() }
        assertTrue("held focus receives no per-hop content events", events.isEmpty())
        assertEquals(spoken, state())
        needle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
        needle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        assertEquals(spoken, state())
        controller.pause().stop().destroy()
    }
}
