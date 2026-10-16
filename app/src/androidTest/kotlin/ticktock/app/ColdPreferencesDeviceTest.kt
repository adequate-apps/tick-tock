// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.app.output.DeviceOutputConnection
import ticktock.app.output.OutputConnection
import ticktock.core.catalogue.Catalogue
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.data.Storage
import ticktock.data.history.Scope
import ticktock.data.prefs.PrefsLoad
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/** Run only through validate-cold-prefs.py: separate invocations with force-stop between them. */
@RunWith(AndroidJUnit4::class)
class ColdPreferencesDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test fun coldPreferencesPhase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("only the disposable proof package may run this test", SANDBOX, context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val phase = requireNotNull(args.getString("coldPhase"))
        require(phase == "prepare" || phase == "restore")
        val nonce = requireNotNull(args.getString("coldNonce"))
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        val evidence = File(context.filesDir, "cold-prefs").also { check(it.isDirectory || it.mkdirs()) }
        val witness = File(evidence, "witness.properties")
        val prefsFile = File(context.filesDir, Storage.PREFS_FILE)
        val previous = Properties()
        if (phase == "prepare") {
            assertFalse("sandbox must be fresh before controls are used", prefsFile.exists())
            assertFalse(witness.exists())
        } else {
            witness.inputStream().use(previous::load)
            assertEquals(nonce, previous.getProperty("nonce"))
            assertNotEquals("restore must use another Android process", previous.getProperty("pid"), Process.myPid().toString())
            assertEquals("unchanged on-disk prefs before constructing the cold graph",
                previous.getProperty("prefsSha256"), sha256(prefsFile.readBytes()))
        }
        val graph = TickTockApp.graph(context)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            if (phase == "prepare") {
                scenario = ActivityScenario.launch(MainActivity::class.java)
                compose.onNodeWithTag("first-run-later").performClick()
                tap("first-run-eb_alto")
                tap("first-run-start")
                tap("capture-1")
                tap("needle-1")
                compose.onNodeWithTag("tabs-1").performClick()
                tap("drone-octave-raise")
                compose.onNodeWithTag("drone-octave-value", useUnmergedTree = true).assertTextEquals("6")
                assertEquals(82, graph.output.selectedMidi())
                scenario.moveToState(Lifecycle.State.CREATED)
                graph.storage.call { }
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.onNodeWithTag("tabs-0").performClick()
                compose.onNodeWithTag("capture-1").assertIsSelected()
                compose.onNodeWithTag("needle-1").assertIsSelected()
                scenario.moveToState(Lifecycle.State.CREATED)
                graph.storage.call { }
                assertTrue(graph.prefs.prefs.value.capturePaused)
                assertTrue(graph.prefs.prefs.value.earsFirst)
                val record = Properties().apply {
                    setProperty("nonce", nonce)
                    setProperty("pid", Process.myPid().toString())
                    setProperty("prefsSha256", sha256(prefsFile.readBytes()))
                    setProperty("expectedDroneMidi", "82")
                    setProperty("expectedInstrument", "eb_alto")
                }
                witness.outputStream().use { record.store(it, "controls-driven cold preference proof") }
            } else {
                assertTrue(graph.prefsLoad is PrefsLoad.Loaded)
                val prefs = graph.prefs.prefs.value
                assertTrue("CAP-02 cold capture paused", prefs.capturePaused)
                assertTrue("EAR-03 cold ears first", prefs.earsFirst)
                assertEquals("eb_alto", prefs.instrumentKey)
                assertEquals(-9, Catalogue.get(prefs.instrumentKey).transposition)
                assertEquals("TON-06 cold octave", 82, prefs.droneMidi)
                assertEquals(82, graph.output.selectedMidi())
                val input = FakeAudioInput(48_000)
                graph.tune.micAllowed = { true }
                graph.tune.makeInput = { input }
                val sent = CopyOnWriteArrayList<OutputCommand>()
                graph.output.makeConnection = {
                    val delegate = DeviceOutputConnection(context)
                    object : OutputConnection by delegate {
                        override fun send(commands: List<OutputCommand>, applied: () -> Unit) {
                            sent.addAll(commands)
                            delegate.send(commands, applied)
                        }
                    }
                }
                scenario = ActivityScenario.launch(MainActivity::class.java)
                compose.onNodeWithTag("first-run-mic").assertDoesNotExist()
                compose.onNodeWithTag("capture-1").assertIsSelected()
                compose.onNodeWithTag("needle-1").assertIsSelected()
                compose.waitUntil(10_000) { input.isOpen }
                val sine = Sine(440.0, 0.1, 48_000)
                repeat(40) { hop ->
                    assertTrue(input.push(sine.next(Hop.frames(48_000))))
                    compose.waitUntil(10_000) { graph.tune.level.value.hop >= hop.toLong() }
                }
                assertEquals(69, graph.tune.state.value.readout.midi)
                compose.onNodeWithTag("readout-sounding", useUnmergedTree = true).assertExists()
                compose.onNodeWithTag("readout-hz", useUnmergedTree = true).assertTextEquals("— Hz")
                compose.onNodeWithTag("readout-cents", useUnmergedTree = true).assertDoesNotExist()
                graph.history.flushAndWait()
                assertTrue("actual settled PCM readings reached paused history", graph.history.status.value.skippedWhilePaused > 0)
                assertEquals("paused readings are valid", 0, graph.history.rejected)
                assertTrue("CAP-02 cold pause excludes actual PCM", runBlocking { graph.history.summaries(Scope.CurrentSession) }.isEmpty())
                assertEquals(0L, graph.tune.diagnostics.value.counters.droppedHops)
                assertEquals(0L, graph.tune.diagnostics.value.counters.analysisErrors)
                compose.onNodeWithTag("tabs-1").performClick()
                compose.onNodeWithTag("drone-octave-value", useUnmergedTree = true).assertTextEquals("6")
                compose.onNodeWithTag("drone-readout-octave", useUnmergedTree = true).assertTextEquals("6")
                tap("drone-toggle")
                compose.waitUntil(10_000) { graph.output.state.value.droneSounding }
                assertEquals(82, sent.filterIsInstance<OutputCommand.VoiceOn>().last().midi)
                assertEquals(82, graph.output.soundingDroneMidi)
                File(evidence, "restore.properties").outputStream().use { stream ->
                    Properties().apply {
                        setProperty("nonce", nonce)
                        setProperty("pid", Process.myPid().toString())
                        setProperty("previousPid", previous.getProperty("pid"))
                        setProperty("prefsSha256", previous.getProperty("prefsSha256"))
                        setProperty("analysedHops", graph.tune.diagnostics.value.counters.hopsAnalysed.toString())
                        setProperty("pausedReadings", graph.history.status.value.skippedWhilePaused.toString())
                        setProperty("savedReadings", "0")
                        setProperty("earsFirstHidden", "true")
                        setProperty("droneCommandMidi", "82")
                        setProperty("verdict", "PASS")
                    }.store(stream, "cold live behavior")
                }
            }
        } finally {
            scenario?.close()
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SANDBOX = "com.adequateapps.metronome.coldproof.debug"
    }
}
