// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.data.Storage
import ticktock.data.crash.CrashLog
import java.io.File

/** The notices the player sees first: one line, one or two keys, and gone once dealt with. */
@RunWith(AndroidJUnit4::class)
class AppNoticesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val filesDir: File get() = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir

    @After
    fun close() {
        scenario?.close()
    }

    @Test
    fun `ERR-02 a crash last time is offered once, and Dismiss puts it away for good`() {
        skipFirstRun()
        CrashLog(filesDir).record(Thread.currentThread(), IllegalStateException("planted"), nickname = "")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("notice-crash").assertIsDisplayed()
        compose.onNodeWithTag("notice-key-2").performClick()
        compose.onNodeWithTag("notice-crash").assertDoesNotExist()
        scenario?.recreate()
        compose.onNodeWithTag("notice-crash").assertDoesNotExist()
    }

    @Test
    fun `ERR-04 a corrupt settings file says so once, keeps the player out of first run, and Dismiss clears it`() {
        File(filesDir, Storage.PREFS_FILE).writeText("{ broken")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("notice-prefs").assertIsDisplayed()
        compose.onNodeWithTag("tabs").assertIsDisplayed()
        compose.onNodeWithTag("notice-prefs-dismiss").performClick()
        compose.onNodeWithTag("notice-prefs").assertDoesNotExist()
    }
}
