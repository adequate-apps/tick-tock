// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.Manifest
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.fakes.Sine
import ticktock.core.prefs.Theme
import ticktock.data.Storage
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import kotlin.math.pow

/** Real-device PCM and pointer dispatch; the runner owns fresh sandbox data and font-scale restoration. */
@RunWith(AndroidJUnit4::class)
class RangeHintDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var graph: AppGraph
    private lateinit var input: FakeAudioInput
    private lateinit var evidence: File
    private var rate = 48_000
    private var deliveredHops = 0L

    @Test fun rangeHintFromPcmAndPointer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("only the disposable proof package may run this test", SANDBOX, context.packageName)
        val args = InstrumentationRegistry.getArguments()
        rate = requireNotNull(args.getString("sampleRate")).toInt()
        require(rate == 44_100 || rate == 48_000)
        val theme = Theme.valueOf(requireNotNull(args.getString("theme")))
        require(theme == Theme.GRIDLINE || theme == Theme.PIT)
        val expectedFontScale = requireNotNull(args.getString("fontScale")).toFloat()
        require(expectedFontScale.isFinite() && expectedFontScale in 1f..2f)
        val run = args.getString("evidenceRun")
            ?: "$rate-${theme.name.lowercase(Locale.ROOT)}-$expectedFontScale-${System.currentTimeMillis()}"
        require(run.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")))
        assertFalse("runner must prepare fresh sandbox prefs", File(context.filesDir, Storage.PREFS_FILE).exists())
        assertFalse("runner must prepare fresh sandbox history", context.getDatabasePath(Storage.DATABASE_FILE).exists())
        evidence = File(context.filesDir, "range-hint/$run")
        check(evidence.mkdirs()) { "evidence directory must be new" }
        val record = Properties().apply {
            setProperty("package", context.packageName)
            setProperty("pid", Process.myPid().toString())
            setProperty("sampleRate", rate.toString())
            setProperty("theme", theme.name)
            setProperty("expectedFontScale", expectedFontScale.toString())
            setProperty("sdk", Build.VERSION.SDK_INT.toString())
            setProperty("deviceFingerprint", Build.FINGERPRINT)
            setProperty("deviceAbis", Build.SUPPORTED_ABIS.joinToString(","))
            setProperty("sourceCommit", args.getString("sourceCommit") ?: "not-supplied")
            setProperty("apkSha256", sha256(File(context.applicationInfo.sourceDir)))
            setProperty("verdict", "RUNNING")
        }
        var scenario: ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        try {
            instrumentation.uiAutomation.grantRuntimePermission(SANDBOX, Manifest.permission.RECORD_AUDIO)
            graph = TickTockApp.graph(context)
            graph.prefs.update { it.copy(firstRunComplete = true, micAsked = true, instrumentKey = "eb_alto",
                capturePaused = true, earsFirst = false, a4Hz = 440.0, theme = theme) }
            input = FakeAudioInput(rate)
            graph.tune.micAllowed = { true }
            graph.tune.makeInput = { input }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                val actual = activity.resources.configuration.fontScale
                record.setProperty("actualFontScale", actual.toString())
                assertEquals("runner-applied font scale", expectedFontScale, actual, 0.01f)
            }
            compose.waitUntil(10_000) { input.isOpen && graph.theme.value == theme }
            compose.onNodeWithTag("capture-1").assertIsSelected()

            tone(40)
            noHint()
            rest()
            tone(42)
            noHint()
            rest()
            tone(44)
            hint()
            record.setProperty("threeDistinctPcmNotes", "PASS")

            tone(44, cents = 7)
            hint()
            compose.onNodeWithTag("readout-cents", true).assertTextEquals("+7")
            val expectedHz = frequency(44, 7)
            val shown = graph.tune.state.value.readout
            compose.onNodeWithTag("readout-hz", true)
                .assertTextEquals(String.format(Locale.ROOT, "%.1f Hz", shown.hz))
            assertTrue(graph.history.flushAndWait())
            val pausedReadings = graph.history.status.value.skippedWhilePaused
            assertTrue("actual accepted PCM still reaches paused history bookkeeping", pausedReadings > 0)
            assertTrue("paused capture stores no accepted PCM", stored().isEmpty())
            record.setProperty("pausedReadings", pausedReadings.toString())
            record.setProperty("savedWhilePaused", "0")
            record.setProperty("liveMidi", shown.midi.toString())
            record.setProperty("liveCents", shown.cents.toString())
            record.setProperty("liveHz", shown.hz.toString())
            record.setProperty("expectedHz", expectedHz.toString())
            record.setProperty("pausedHintAndLiveReadout", "PASS")
            screenshot("paused-hint.png")

            compose.onNodeWithTag("capture-0").performScrollTo().performClick()
            compose.onNodeWithTag("capture-0").assertIsSelected()
            assertFalse(graph.prefs.prefs.value.capturePaused)
            tone(44, cents = 7)
            hint()
            val captured = stored()
            assertTrue("Capture On stores actual accepted PCM", captured.isNotEmpty())
            assertTrue(captured.all { it.instrument == "eb_alto" && it.transposition == -9 && it.a4 == 440.0 })
            val last = captured.last()
            assertEquals(44, last.midi)
            assertEquals(7.0, last.cents, 0.5)
            assertEquals(expectedHz, last.hz, hzTolerance(expectedHz))
            record.setProperty("savedAfterCaptureEnabled", captured.size.toString())
            record.setProperty("lastStoredCents", last.cents.toString())
            record.setProperty("lastStoredHz", last.hz.toString())
            record.setProperty("captureEnabled", "PASS")
            screenshot("captured-hint.png")

            clickHintChevron()
            compose.onNodeWithTag("instruments-pane").assertIsDisplayed()
            compose.onNodeWithTag("instrument-bb_tenor").performScrollTo().assertIsDisplayed()
            screenshot("instrument-picker.png")
            compose.onNodeWithTag("back").performClick()
            compose.onNodeWithTag("tune").assertExists()
            hint()
            assertEquals("picker Back preserves all saved readings", captured, stored())
            assertStream()
            record.setProperty("chevronPointerAndBack", "PASS")
            screenshot("returned-hint.png")

            clickHintChevron()
            compose.onNodeWithTag("instrument-bb_tenor").performScrollTo().performClick()
            compose.onNodeWithTag("tune").assertExists()
            assertEquals("bb_tenor", graph.prefs.prefs.value.instrumentKey)
            noHint() // No new PCM is needed to hide the former instrument's judgment.
            assertEquals("selection preserves earlier history", captured, stored())
            rest()
            tone(40)
            noHint()
            rest()
            tone(42)
            noHint()
            val finalRows = stored()
            assertEquals("the old alto readings are unchanged", captured, finalRows.take(captured.size))
            val tenorRows = finalRows.drop(captured.size)
            assertTrue("fresh PCM remains capturable after instrument selection", tenorRows.isNotEmpty())
            assertTrue(tenorRows.all { it.instrument == "bb_tenor" && it.transposition == -14 && it.a4 == 440.0 })
            assertTrue(tenorRows.any { it.midi == 40 } && tenorRows.any { it.midi == 42 })
            assertStream()
            record.setProperty("savedAfterSelection", finalRows.size.toString())
            record.setProperty("tenorSavedReadings", tenorRows.size.toString())
            record.setProperty("pickerSelectionContinuity", "PASS")
            compose.onNodeWithTag("readout-hz", true).performScrollTo().assertIsDisplayed()
            screenshot("selected-instrument.png")
            record.setProperty("verdict", "PASS")
        } catch (error: Throwable) {
            failure = error
            record.setProperty("verdict", "FAIL")
            record.setProperty("failureClass", error.javaClass.name)
            record.setProperty("failureMessage", error.message.orEmpty())
            try { screenshot("failure.png", requireForeground = false) } catch (captureFailure: Throwable) { error.addSuppressed(captureFailure) }
            throw error
        } finally {
            record.setProperty("deliveredHops", deliveredHops.toString())
            if (this::graph.isInitialized) {
                val diagnostics = graph.tune.diagnostics.value
                record.setProperty("sampledAnalysedHops", diagnostics.counters.hopsAnalysed.toString())
                record.setProperty("sampledDroppedHops", diagnostics.counters.droppedHops.toString())
                record.setProperty("sampledAnalysisErrors", diagnostics.counters.analysisErrors.toString())
                record.setProperty("historyRejected", graph.history.rejected.toString())
            }
            if (this::input.isInitialized) {
                record.setProperty("inputStartsBeforeCleanup", input.startCalls.toString())
                record.setProperty("inputStopsBeforeCleanup", input.stopCalls.toString())
            }
            try {
                scenario?.close()
            } catch (cleanupFailure: Throwable) {
                record.setProperty("verdict", "FAIL")
                record.setProperty("cleanupFailure", cleanupFailure.toString())
                if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
            } finally {
                File(evidence, "result.properties").outputStream().use { record.store(it, "range hint device proof") }
            }
        }
    }

    private fun frequency(midi: Int, cents: Int) = 440.0 * 2.0.pow((midi - 69 + cents / 100.0) / 12)
    private fun hzTolerance(hz: Double) = hz * (2.0.pow(0.5 / 1200) - 1.0) + 0.1

    private fun tone(midi: Int, cents: Int = 0) {
        val hz = frequency(midi, cents)
        val sine = Sine(hz, 0.1, rate)
        repeat(24) { deliver(sine.next(Hop.frames(rate))) }
        runBlocking { withTimeout(10_000) {
            graph.tune.state.first { it.readout.midi == midi && it.readout.cents == cents }
        } }
        assertEquals(hz, graph.tune.state.value.readout.hz!!, hzTolerance(hz))
        compose.waitForIdle()
        assertStream()
    }

    private fun rest() { repeat(12) { deliver(FloatArray(Hop.frames(rate))) } }

    private fun deliver(samples: FloatArray) {
        assertTrue(input.push(samples))
        deliveredHops++
        runBlocking { withTimeout(10_000) { graph.tune.level.first { it.hop >= deliveredHops - 1 } } }
        assertEquals("every delivered hop completes", deliveredHops - 1, graph.tune.level.value.hop)
    }

    private fun hint() {
        compose.onNodeWithText("Below alto range").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("range-hint-instrument").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Below alto range").assertIsDisplayed()
        assertTrue(graph.tune.rangeHint.value?.matches(graph.prefs.publication.value) == true)
    }

    private fun noHint() {
        compose.onNodeWithTag("range-hint").assertDoesNotExist()
        compose.onNodeWithTag("range-hint-instrument").assertDoesNotExist()
    }

    private fun clickHintChevron() {
        assertForeground()
        val action = compose.onNodeWithTag("range-hint-instrument").performScrollTo().assertIsDisplayed()
        val density = action.fetchSemanticsNode().layoutInfo.density.density
        action.performTouchInput { click(Offset(center.x * 2f - 20f * density, center.y)) }
    }

    private data class Stored(
        val hop: Long, val midi: Int, val hz: Double, val cents: Double,
        val instrument: String, val transposition: Int, val a4: Double,
    )

    private fun stored(): List<Stored> {
        assertTrue(graph.history.flushAndWait())
        val path = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(Storage.DATABASE_FILE).path
        val rows = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""SELECT h.hop, h.midi_sounding, h.hz, h.cents,
                r.instrument_key, r.transposition, r.a4_hz
                FROM readings h JOIN runs r ON h.run_id = r.id ORDER BY h.rowid""", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Stored(cursor.getLong(0), cursor.getInt(1), cursor.getDouble(2),
                        cursor.getDouble(3), cursor.getString(4), cursor.getInt(5), cursor.getDouble(6)))
                }
            }
        }
        assertEquals("accepted readings appear exactly once", rows.size, rows.map { it.hop }.distinct().size)
        return rows
    }

    private fun assertStream() {
        assertTrue(input.isOpen)
        assertEquals(1, input.openCount)
        assertEquals("navigation and capture controls preserve input", 1, input.startCalls)
        assertEquals(0, input.stopCalls)
        assertEquals(0, graph.history.rejected)
        assertEquals(0L, graph.tune.diagnostics.value.counters.droppedHops)
        assertEquals(0L, graph.tune.diagnostics.value.counters.analysisErrors)
    }

    private fun assertForeground() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(100, 5_000)
        val active = checkNotNull(automation.rootInActiveWindow) { "active accessibility window is unavailable" }
        assertEquals("the proof app must be unobstructed by another window", SANDBOX, active.packageName?.toString())
    }

    private fun screenshot(name: String, requireForeground: Boolean = true) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        if (requireForeground) assertForeground()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "device screenshot was unavailable" }
        try {
            File(evidence, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SANDBOX = "com.adequateapps.metronome.coldproof.debug"
    }
}
