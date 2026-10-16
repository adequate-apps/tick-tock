// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.core.config.PersonalConfig
import ticktock.core.config.PersonalConfigCodec
import ticktock.core.config.PortableSettings
import ticktock.core.prefs.Theme
import java.io.File

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class SettingsExtrasScreenshotTest(private val scale: Float, private val theme: Theme, private val width: Int) {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    @After fun close() { scenario?.close(); RuntimeEnvironment.setFontScale(1f) }
    private fun golden(name: String) {
        val path = "src/test/screenshots/settings-$name-${width}x800-fs$scale-${theme.name.lowercase()}.png"
        compose.waitForIdle()
        when (name) {
            // These goldens include the dimmed frame behind the dialog.
            "import", "diagnostics" -> captureScreenRoboImage(path)
            // About's golden is the dialog canvas, including its outer padding.
            "about" -> compose.onNode(isRoot() and hasAnyDescendant(isDialog()))
                .captureToImage().asAndroidBitmap().captureRoboImage(path)
            else -> compose.onNodeWithTag("frame").captureRoboImage(path)
        }
    }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }

    @Test fun `practice storage import diagnostics and About remain reachable at every text size`() {
        RuntimeEnvironment.setQualifiers("w${width}dp-h800dp-xhdpi")
        RuntimeEnvironment.setFontScale(scale)
        val context = RuntimeEnvironment.getApplication()
        val graph = TickTockApp.graph(context)
        graph.tune.micAllowed = { false }
        graph.prefs.update { it.copy(firstRunComplete = true, theme = theme) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-noise-floor").performScrollTo()
        golden("practice")
        compose.onNodeWithTag("settings-visual-offset").performScrollTo()
        golden("timing")
        compose.onNodeWithTag("settings-clear-history").performScrollTo()
        golden("storage")
        val document = File(context.cacheDir, "preview.json")
        document.writeText(PersonalConfigCodec.write(PersonalConfig(settings = PortableSettings(a4Hz = 442.0, noiseFloorDb = -60))))
        click("config-import")
        scenario!!.onActivity { activity ->
            val shadow = shadowOf(activity)
            val request = shadow.nextStartedActivityForResult.intent
            shadow.receiveResult(request, Activity.RESULT_OK, Intent().setData(Uri.fromFile(document)))
        }
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithTag("config-preview").fetchSemanticsNodes().isNotEmpty() }
        golden("import")
        click("config-cancel")
        click("settings-diagnostics")
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithTag("diag-input").fetchSemanticsNodes().isNotEmpty() }
        golden("diagnostics")
        compose.onNodeWithTag("diag-prefs-failures").performScrollTo().assertIsDisplayed()
        click("diagnostics-back")
        click("settings-about")
        golden("about")
        compose.onNodeWithTag("about-privacy").performScrollTo().assertIsDisplayed()
    }
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "fs{0}-{1}-w{2}")
        fun cases(): List<Array<Any>> = TableScreenshotTest.cases()
    }
}
