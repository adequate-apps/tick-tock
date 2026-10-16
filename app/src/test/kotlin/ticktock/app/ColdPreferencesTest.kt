// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.app.output.TestOutputConnection
import ticktock.core.catalogue.Catalogue
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.data.Storage
import ticktock.data.history.Scope
import ticktock.data.prefs.PrefsLoad
import java.io.File

/** The Activity supplies the writes; a distinct graph must subsequently read the files. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class ColdPreferencesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var graph: AppGraph? = null
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    @After fun close() {
        scenario?.close()
        graph?.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test fun `CAP-02 EAR-03 TON-06 controls survive a closed graph and fresh file load`() {
        val context = RuntimeEnvironment.getApplication()
        val original = TickTockApp.graph(context).also { graph = it }
        original.tune.micAllowed = { false }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("first-run-later").performClick()
        tap("first-run-eb_alto")
        tap("first-run-start")
        tap("capture-1")
        tap("needle-1")
        compose.onNodeWithTag("capture-1").assertIsSelected()
        compose.onNodeWithTag("needle-1").assertIsSelected()
        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("drone-readout-octave", useUnmergedTree = true).assertTextEquals("5")
        tap("drone-octave-raise")
        compose.onNodeWithTag("drone-octave-value", useUnmergedTree = true).assertTextEquals("6")
        assertEquals(82, original.output.selectedMidi())

        // onStop invokes the production background flush. A storage barrier waits for it;
        // no test serializer, explicit save, or preference update supplies the result.
        scenario!!.moveToState(Lifecycle.State.CREATED)
        original.storage.call { }
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("tabs-0").performClick()
        compose.onNodeWithTag("capture-1").assertIsSelected()
        compose.onNodeWithTag("needle-1").assertIsSelected()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        original.storage.call { }
        scenario!!.close()
        scenario = null
        original.close()
        graph = null
        assertTrue(File(context.filesDir, Storage.PREFS_FILE).isFile)

        val link = TestOutputConnection()
        val restored = AppGraph(context, makeOutput = { link }).also { graph = it }
        assertNotSame(original, restored)
        assertNotSame(original.prefs, restored.prefs)
        assertTrue(restored.prefsLoad is PrefsLoad.Loaded)
        val prefs = restored.prefs.prefs.value
        assertTrue("CAP-02 paused after file load", prefs.capturePaused)
        assertTrue("EAR-03 ears first after file load", prefs.earsFirst)
        assertEquals("eb_alto", prefs.instrumentKey)
        assertEquals(-9, Catalogue.get(prefs.instrumentKey).transposition)
        assertEquals("TON-06 raised sounding pitch after file load", 82, prefs.droneMidi)
        assertEquals(82, restored.output.selectedMidi())

        val input = FakeAudioInput(48_000)
        restored.tune.micAllowed = { true }
        restored.tune.makeInput = { input }
        compose.runOnIdle { restored.onForeground() }
        val sine = Sine(440.0, 0.1, 48_000)
        repeat(40) { hop ->
            assertTrue(input.push(sine.next(Hop.frames(48_000))))
            compose.waitUntil(10_000) { restored.tune.level.value.hop >= hop.toLong() }
        }
        assertEquals(69, restored.tune.state.value.readout.midi)
        restored.history.flushAndWait()
        assertTrue("real accepted readings were excluded while paused", restored.history.status.value.skippedWhilePaused > 0)
        assertEquals("paused readings are valid", 0, restored.history.rejected)
        assertTrue(runBlocking { restored.history.summaries(Scope.CurrentSession) }.isEmpty())
        compose.runOnIdle { restored.output.startDrone() }
        assertEquals(82, link.sent.filterIsInstance<OutputCommand.VoiceOn>().last().midi)
    }
}
