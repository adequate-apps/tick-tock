// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.design.GridlineThemes
import com.github.takahirom.roborazzi.captureRoboImage
import ticktock.core.prefs.Prefs

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class MicAccessTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() { scenario?.close() }

    @Test
    fun `denied microphone gates show the matching notice and a labelled icon`() {
        skipFirstRun(Prefs(firstRunComplete = true, micAsked = true))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertDenied("gate-mic_denied", GridlineThemes.gridline.yellowBg, GridlineThemes.gridline.attention)
        compose.onNodeWithTag("readout").captureRoboImage("src/test/screenshots/tune-mic-denied.png")
        compose.onNodeWithTag("allow-mic").performClick()
        denyRequest()
        assertDenied("gate-mic_denied_always", GridlineThemes.gridline.redBg, GridlineThemes.gridline.danger)
        compose.onNodeWithTag("readout").captureRoboImage("src/test/screenshots/tune-mic-denied-always.png")
    }

    private fun assertDenied(tag: String, background: Color, rule: Color) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag("$tag-text", useUnmergedTree = true).assertTextEquals("Denied.")
        compose.onNodeWithContentDescription("Mic denied", useUnmergedTree = true).assertIsDisplayed()
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        assertEquals(background, pixels[pixels.width - 1, 0])
        assertEquals(rule, pixels[0, 0])
    }

    @Test
    fun `the denied microphone key names System settings`() {
        skipFirstRun(Prefs(firstRunComplete = true, micAsked = true))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("allow-mic").performScrollTo().performClick()
        denyRequest()
        compose.onNodeWithTag("open-settings-mic").assertTextEquals("System settings")
    }

    @Suppress("DEPRECATION")
    private fun denyRequest() {
        scenario?.onActivity { activity ->
            val request = shadowOf(activity).lastRequestedPermission
            assertNotNull(request)
            assertEquals(listOf(Manifest.permission.RECORD_AUDIO), request.requestedPermissions.toList())
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        }
    }

    @Test
    fun `a revoked microphone can be requested again before a denial in this session opens settings`() {
        skipFirstRun(Prefs(firstRunComplete = true, micAsked = true))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("gate-mic_denied").assertExists()
        compose.onNodeWithTag("allow-mic").performScrollTo().performClick()
        denyRequest()
        compose.onNodeWithTag("gate-mic_denied_always").assertExists()
        compose.onNodeWithTag("open-settings-mic").performScrollTo().assertIsDisplayed()
        scenario?.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario?.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithTag("open-settings-mic").assertExists()
    }
}
