// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.design.components.Button

@RunWith(AndroidJUnit4::class)
class ContactButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `timed buttons report initial event times independently of hold and keep accessible activation`() {
        val contacts = mutableListOf<Long>()
        val expected = mutableListOf<Long>()
        var clicks = 0
        compose.setContent { GridlineTheme {
            Button("Tap", { clicks++ }, Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) expected += event.changes.first().uptimeMillis
                    }
                }
            }, onContact = { contacts += it }, tag = "tap")
        } }
        val button = compose.onNodeWithTag("tap")
        for (hold in listOf(0L, 80L, 250L)) {
            button.performTouchInput { down(center) }
            compose.runOnIdle { assertEquals(expected, contacts) }
            button.performTouchInput { advanceEventTime(hold); up() }
            compose.runOnIdle { assertEquals(expected, contacts); assertEquals(0, clicks) }
        }
        button.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, clicks); assertEquals(3, contacts.size) }
    }
}
