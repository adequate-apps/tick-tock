// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.core.config.*
import ticktock.core.detect.Reading
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class SettingsExtrasTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    @After fun close() { scenario?.close() }
    private fun launch() {
        graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.tune.micAllowed = { false }
        graph.prefs.update { it.copy(firstRunComplete = true) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("open-settings").performClick()
    }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun awaitState(predicate: () -> Boolean) { compose.waitUntil(5_000) { compose.waitForIdle(); predicate() } }
    private fun chooseDocument(expectedAction: String, file: File) {
        scenario!!.onActivity { activity ->
            val shadow = shadowOf(activity)
            val requested = shadow.nextStartedActivityForResult.intent
            assertEquals(expectedAction, requested.action)
            shadow.receiveResult(requested, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        }
    }

    @Test fun `settings controls preserve edits and clearing history requires confirmation`() {
        launch()
        click("settings-noise-floor-lower")
        click("settings-ears-first-0")
        click("settings-announce-0")
        click("settings-visual-offset-raise")
        assertEquals(-46, graph.prefs.prefs.value.noiseFloorDb)
        assertTrue(graph.prefs.prefs.value.earsFirst && graph.prefs.prefs.value.announceSettledNotes)
        assertEquals(1, graph.prefs.prefs.value.visualOffsetMs)
        runBlocking {
            graph.history.accept(Reading(0, 69, 440.0, 0.0, 440.0, 0), true)
            graph.history.flushAndWait()
        }
        click("settings-clear-history")
        click("clear-cancel")
        assertEquals(1, runBlocking { graph.history.sessions() }.size)
        click("settings-clear-history")
        click("clear-confirm")
        awaitState { !graph.table.state.value.busy && graph.table.state.value.snapshot?.sessions?.isEmpty() == true }
        assertTrue(runBlocking { graph.history.sessions() }.isEmpty())
        assertTrue(graph.prefs.flushAndWait().isSuccess)
        val restored = (ticktock.core.prefs.PrefsSchema.read(File(RuntimeEnvironment.getApplication().filesDir, "prefs.json").readText()) as ticktock.core.prefs.ReadResult.Ok).result.prefs
        assertEquals(graph.prefs.prefs.value, restored)
    }

    @Test fun `configuration import previews cancels and applies before a matching document export`() {
        launch()
        val document = File(RuntimeEnvironment.getApplication().cacheDir, "chosen-settings.json")
        val config = PersonalConfig(settings = PortableSettings(a4Hz = 442.0, noiseFloorDb = -60),
            hornProfiles = listOf(HornProfile("alto", "eb_alto", "Silver alto")))
        document.writeText(PersonalConfigCodec.write(config))
        click("config-import")
        chooseDocument(Intent.ACTION_OPEN_DOCUMENT, document)
        awaitState { compose.onAllNodesWithTag("config-preview").fetchSemanticsNodes().isNotEmpty() }
        scenario!!.recreate()
        compose.onNodeWithTag("config-preview").assertExists()
        assertEquals(440.0, graph.prefs.prefs.value.a4Hz, 0.0)
        click("config-cancel")
        assertEquals(440.0, graph.prefs.prefs.value.a4Hz, 0.0)
        click("config-import")
        chooseDocument(Intent.ACTION_OPEN_DOCUMENT, document)
        awaitState { compose.onAllNodesWithTag("config-preview").fetchSemanticsNodes().isNotEmpty() }
        scenario!!.recreate()
        compose.onNodeWithTag("config-preview").assertExists()
        click("config-apply")
        awaitState { graph.prefs.prefs.value.a4Hz == 442.0 && compose.onAllNodesWithTag("config-preview").fetchSemanticsNodes().isEmpty() }
        assertEquals(-60, graph.prefs.prefs.value.noiseFloorDb)
        assertEquals(config.hornProfiles, graph.prefs.prefs.value.personalConfig.hornProfiles)
        click("config-export")
        val request = arrayOfNulls<Intent>(1)
        scenario!!.onActivity { request[0] = shadowOf(it).nextStartedActivityForResult.intent }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request[0]!!.action)
        scenario!!.recreate()
        scenario!!.onActivity { shadowOf(it).receiveResult(request[0], Activity.RESULT_OK, Intent().setData(Uri.fromFile(document))) }
        awaitState { compose.onAllNodesWithText("Settings exported").fetchSemanticsNodes().isNotEmpty() }
        val exported = (PersonalConfigCodec.parse(document.readText()) as ParseResult.Ok).config
        assertEquals(442.0, exported.settings.a4Hz!!, 0.0)
        assertEquals(config.hornProfiles, exported.hornProfiles)
        assertFalse(document.readText().contains("bluetoothVisualOffsetMs"))
    }

    @Test fun `saved horn selection applies imported identity and setup can be replayed twice`() {
        launch()
        graph.prefs.update { it.copy(personalConfig = PersonalConfig(hornProfiles = listOf(HornProfile("alto", "eb_alto", "Silver alto")))) }
        click("setting-instrument")
        click("horn-profile-alto")
        assertEquals("eb_alto", graph.prefs.prefs.value.instrumentKey)
        assertEquals("Silver alto", graph.prefs.prefs.value.hornNickname)
        repeat(2) {
            click("settings-first-run")
            compose.onNodeWithTag("first-run-mic").assertExists()
            click("first-run-later")
            click("first-run-start")
            assertTrue(graph.prefs.prefs.value.firstRunComplete)
        }
    }

    @Test fun `DST-04 About has version bundled licences and the privacy link`() {
        launch()
        click("settings-about")
        compose.onNodeWithText(BuildConfig.VERSION_NAME).assertExists()
        click("about-licence")
        awaitState { compose.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty() }
        click("about-document-back")
        click("about-notices")
        awaitState { compose.onAllNodesWithText("IBM Plex", substring = true).fetchSemanticsNodes().isNotEmpty() }
        click("about-document-back")
        click("about-privacy")
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/adequate-apps/tick-tock/blob/main/docs/privacy.md", intent.dataString)
    }
    @Test fun `ordinary instrument choices clear a named identity before capture`() {
        launch()
        graph.prefs.update { it.copy(personalConfig = PersonalConfig(hornProfiles = listOf(HornProfile("alto", "eb_alto", "Silver alto")))) }
        for ((index, instrument) in listOf("eb_alto", "flute_c").withIndex()) {
            click("setting-instrument")
            click("horn-profile-alto")
            click("setting-instrument")
            compose.onNodeWithTag("instrument-eb_alto").assertIsNotSelected()
            click("instrument-$instrument")
            assertEquals("", graph.prefs.prefs.value.hornNickname)
            graph.captureHistory.accept(Reading(index.toLong(), 69, 440.0, 0.0, 440.0, 0), true)
            runBlocking { graph.history.flushAndWait() }
            assertTrue(runBlocking { graph.history.summaries(ticktock.data.history.Scope.CurrentSession) }
                .any { it.label.instrumentKey == instrument && it.label.nickname.isEmpty() })
        }
        graph.prefs.update { it.copy(instrumentKey = "eb_alto", hornNickname = "Silver alto") }
        click("settings-first-run")
        click("first-run-later")
        click("first-run-eb_alto")
        assertEquals("", graph.prefs.prefs.value.hornNickname)
    }

    @Test fun `deep import displays failure preserves preferences and leaves controls usable`() {
        launch()
        val before = graph.prefs.prefs.value
        val document = File(RuntimeEnvironment.getApplication().cacheDir, "deep.json")
        document.writeText("[".repeat(10_000) + "0" + "]".repeat(10_000))
        click("config-import")
        chooseDocument(Intent.ACTION_OPEN_DOCUMENT, document)
        awaitState { compose.onAllNodesWithText("Phone refused invalid settings").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, graph.prefs.prefs.value)
        compose.onNodeWithTag("config-preview").assertDoesNotExist()
        click("settings-noise-floor-lower")
        assertEquals(before.noiseFloorDb - 1, graph.prefs.prefs.value.noiseFloorDb)
    }

    @Test fun `configuration read finishes after recreation and its preview still cancels and applies`() {
        launch()
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://test.settings/slow")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val config = PersonalConfig(settings = PortableSettings(a4Hz = 443.0))
        val input = object : java.io.ByteArrayInputStream(PersonalConfigCodec.write(config).toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, input)
        click("config-import")
        scenario!!.onActivity { activity ->
            val requested = shadowOf(activity).nextStartedActivityForResult.intent
            shadowOf(activity).receiveResult(requested, Activity.RESULT_OK, Intent().setData(uri))
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try { scenario!!.recreate() } finally { release.countDown() }
        awaitState { compose.onAllNodesWithTag("config-preview").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(440.0, graph.prefs.prefs.value.a4Hz, 0.0)
        click("config-apply")
        awaitState { graph.prefs.prefs.value.a4Hz == 443.0 }
    }

    @Test fun `restored import reports how to recover when its retained payload was lost`() {
        launch()
        val document = File(RuntimeEnvironment.getApplication().cacheDir, "pending.json")
        document.writeText(PersonalConfigCodec.write(PersonalConfig(settings = PortableSettings(a4Hz = 442.0))))
        click("config-import")
        chooseDocument(Intent.ACTION_OPEN_DOCUMENT, document)
        awaitState { compose.onAllNodesWithTag("config-preview").fetchSemanticsNodes().isNotEmpty() }
        // Model a new process owner while retaining the activity's small workflow marker.
        scenario!!.onActivity { graph.configuration.cancel() }
        scenario!!.recreate()
        compose.onNodeWithText("Phone interrupted settings import; choose the file again").assertExists()
        assertEquals(440.0, graph.prefs.prefs.value.a4Hz, 0.0)
        click("config-import")
        chooseDocument(Intent.ACTION_OPEN_DOCUMENT, document)
        awaitState { compose.onAllNodesWithTag("config-preview").fetchSemanticsNodes().isNotEmpty() }
        click("config-cancel")
        scenario!!.recreate()
        compose.onNodeWithTag("config-preview").assertDoesNotExist()
    }

}
