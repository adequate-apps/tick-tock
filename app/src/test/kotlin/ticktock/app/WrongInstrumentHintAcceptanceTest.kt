// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.app.Activity
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import ticktock.app.debug.InputSeam
import ticktock.app.output.TestOutputConnection
import ticktock.core.catalogue.Catalogue
import ticktock.core.config.DisplayMode
import ticktock.core.config.PersonalConfig
import ticktock.core.config.PersonalConfigCodec
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.Hop
import ticktock.core.detect.ResponseMode
import ticktock.core.detect.fakes.Sine
import ticktock.core.prefs.Theme
import ticktock.core.ranges.UserRangeOverride
import ticktock.core.ranges.WrittenRange
import ticktock.data.Storage
import ticktock.data.history.Scope
import ticktock.design.GridlineThemes
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/** Actual PCM and visible controls establish the observer's place in the live path. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class WrongInstrumentHintAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private lateinit var input: FakeAudioInput
    private val output = TestOutputConnection()
    private var rate = 48_000
    private var deliveredHops = 0L

    @After fun close() {
        scenario?.close()
        InputSeam.override = null
    }

    private fun launch(sampleRate: Int = 48_000, paused: Boolean = false, tools: Boolean = false,
                       instrument: String = "eb_alto") {
        rate = sampleRate
        graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.output.makeConnection = { output }
        graph.prefs.update { it.copy(firstRunComplete = true, instrumentKey = instrument,
            capturePaused = paused, droneMidi = 57, metroBpm = 137) }
        input = FakeAudioInput(rate)
        InputSeam.override = input
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { input.isOpen }
        if (tools) {
            compose.runOnIdle { graph.output.startDrone(); graph.output.startMetro() }
            // Drain opening/route publications before the first one-hop input barrier.
            compose.waitForIdle()
        }
    }

    // Frequency truth is independent of the production pitch/range helpers.
    private fun frequency(midi: Int, cents: Double = 0.0): Double =
        graph.prefs.prefs.value.a4Hz * 2.0.pow((midi - 69 + cents / 100.0) / 12.0)

    private fun hzTolerance(hz: Double) = hz * (2.0.pow(0.5 / 1200.0) - 1.0) + 0.1

    private fun deliver(samples: FloatArray, hops: Int) {
        assertTrue("the original microphone stays open", input.push(samples))
        deliveredHops += hops
        runBlocking {
            withTimeout(5_000) { graph.tune.level.first { it.hop >= deliveredHops - 1 } }
        }
        assertEquals("each delivered hop is accounted for", deliveredHops - 1, graph.tune.level.value.hop)
    }

    private fun feed(sine: Sine?, hops: Int, batch: Int = 1) {
        var remaining = hops
        while (remaining > 0) {
            val count = minOf(remaining, batch)
            val frames = Hop.frames(rate) * count
            deliver(sine?.next(frames) ?: FloatArray(frames), count)
            remaining -= count
        }
        compose.waitForIdle()
        assertEquals(0L, graph.tune.diagnostics.value.counters.droppedHops)
        assertEquals(0L, graph.tune.diagnostics.value.counters.analysisErrors)
        assertEquals(0, graph.history.rejected)
    }

    private fun tone(midi: Int, cents: Double = 0.0, hops: Int = 24, batch: Int = 1) {
        feed(Sine(frequency(midi, cents), 0.1, rate), hops, batch)
        compose.waitUntil(5_000) {
            graph.tune.state.value.readout.let { it.midi == midi && it.cents == cents.roundToInt() }
        }
        val shown = graph.tune.state.value.readout
        assertEquals("independent cents truth for sounding MIDI $midi", cents.roundToInt(), shown.cents)
        val expectedHz = frequency(midi, cents)
        assertEquals("independent Hz truth for sounding MIDI $midi", expectedHz, shown.hz!!, hzTolerance(expectedHz))
    }

    private fun rest(hops: Int = 12, batch: Int = 1) = feed(null, hops, batch)
    private fun tap(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag == "back" || tag == "open-settings") node.performClick()
        else node.performScrollTo().performClick()
    }
    private val action get() = hasText("Instrument") and hasClickAction() and hasAnyAncestor(hasTestTag("readout"))

    private fun noHint() {
        compose.onAllNodes(action).assertCountEquals(0)
        for (cause in listOf("Below alto range", "Above alto range", "Outside alto range")) {
            compose.onNode(hasText(cause) and hasAnyAncestor(hasTestTag("readout")), true).assertDoesNotExist()
        }
    }

    private fun hint(cause: String = "Below alto range") {
        compose.onNode(hasText(cause) and hasAnyAncestor(hasTestTag("readout")), true)
            .performScrollTo().assertIsDisplayed()
        compose.onNode(action).assertHasClickAction().assertIsDisplayed()
    }

    private fun trigger(vararg notes: Int, cause: String = "Below alto range") {
        notes.forEachIndexed { index, midi ->
            if (index > 0) rest()
            tone(midi)
            if (index < 2) noHint()
        }
        hint(cause)
    }

    private data class Stored(val hop: Long, val midi: Int, val hz: Double, val cents: Double,
                              val instrument: String, val transposition: Int, val a4: Double)

    /** Read only what the real detector and capture owner sent to the real store. */
    private fun stored(): List<Stored> {
        assertTrue(graph.history.flushAndWait())
        val path = RuntimeEnvironment.getApplication().getDatabasePath(Storage.DATABASE_FILE).path
        return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""SELECT h.hop, h.midi_sounding, h.hz, h.cents,
                r.instrument_key, r.transposition, r.a4_hz
                FROM readings h JOIN runs r ON h.run_id = r.id ORDER BY h.rowid""", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Stored(cursor.getLong(0), cursor.getInt(1), cursor.getDouble(2),
                        cursor.getDouble(3), cursor.getString(4), cursor.getInt(5), cursor.getDouble(6)))
                }
            }
        }
    }

    private fun assertAccepted(notes: Set<Int>) {
        val saved = stored()
        assertEquals(notes, saved.map { it.midi }.toSet())
        assertEquals("a detector reading reaches history once", saved.size, saved.map { it.hop }.distinct().size)
        assertTrue(saved.all { it.instrument == "eb_alto" && it.transposition == -9 && it.a4 == 440.0 })
        for (midi in notes) {
            val last = saved.last { it.midi == midi }
            assertEquals(frequency(midi), last.hz, hzTolerance(frequency(midi)))
            assertEquals(0.0, last.cents, 0.5)
        }
        val summaries = runBlocking { graph.history.summaries(Scope.CurrentSession) }
        assertEquals(saved.size.toLong(), summaries.sumOf { it.n })
        assertTrue("accepted tones retain their attack counts", summaries.all { it.attacks > 0 })
    }

    private fun ink(tag: String, color: Color): Int {
        val pixels = compose.onNodeWithTag(tag, true).performScrollTo().captureToImage().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y] == color) count++
        return count
    }

    @Test fun `HNT-01 HNT-08 three low PCM notes show the notice while live cents Hz trace and history continue`() {
        launch()
        trigger(40, 42, 44)
        assertAccepted(setOf(40, 42, 44))
        val before = stored()
        val traceBefore = graph.tune.trace.value.cents.toList()
        tone(44, cents = 7.0)
        hint()
        compose.onNodeWithTag("readout-cents", true).assertTextEquals("+7")
        compose.onNodeWithTag("readout-hz", true).assertTextEquals("${graph.tune.state.value.readout.hz} Hz")
        assertTrue(ink("readout-cents", GridlineThemes.gridline.textDisabled) > 0)
        assertTrue("live trace keeps moving while the hint is present", graph.tune.trace.value.cents.toList() != traceBefore)
        val after = stored()
        assertEquals(before, after.take(before.size))
        assertTrue(after.size > before.size)
        assertEquals(7.0, after.last().cents, 0.2)
        assertEquals(frequency(44, 7.0), after.last().hz, 0.2)
        assertEquals(44, after.last().midi)
        val cause = hasText("Below alto range")
        compose.onAllNodes((cause or hasAnyDescendant(cause)) and
            SemanticsMatcher("has a live region") { it.config.getOrNull(SemanticsProperties.LiveRegion) != null },
            useUnmergedTree = true).assertCountEquals(0)
        tone(44, cents = -8.0)
        hint()
        compose.onNodeWithTag("readout-cents", true).assertTextEquals("−8")
    }

    @Test fun `HNT-01 three high PCM notes at 44100 show a truthful above-range cause`() {
        launch(sampleRate = 44_100)
        trigger(95, 97, 99, cause = "Above alto range")
        assertAccepted(setOf(95, 97, 99))
        compose.onNodeWithTag("readout-cents", true).assertTextEquals("+0")
        compose.onNodeWithText("Below alto range", true).assertDoesNotExist()
    }

    @Test fun `HNT-01 mixed low and high strays have an outside-range cause`() {
        launch()
        trigger(40, 95, 42, cause = "Outside alto range")
        assertAccepted(setOf(40, 95, 42))
        compose.onNodeWithText("Below alto range", true).assertDoesNotExist()
        compose.onNodeWithText("Above alto range", true).assertDoesNotExist()
    }

    @Test fun `HNT-02 rests and unrelated preferences preserve two accepted strays until the third`() {
        launch()
        tone(40); rest(); tone(42); rest()
        noHint()
        val beforeRest = stored()
        rest(hops = Hop.count(30.0), batch = 8)
        assertEquals("silent input has no accepted readings with which to age the window", beforeRest, stored())
        compose.runOnIdle { graph.prefs.update { it.copy(theme = Theme.DAYLIGHT, displayMode = DisplayMode.CONCERT,
            a4Hz = 442.0, responseMode = ResponseMode.FAST, keepAwake = false) } }
        tone(44)
        hint()
        compose.runOnIdle { graph.prefs.update { it.copy(theme = Theme.GRIDLINE, keepAwake = true) } }
        hint()
        assertEquals(beforeRest, stored().take(beforeRest.size))
    }

    @Test fun `HNT-02 accepted readings in multi-hop batches age a visible hint without wall time`() {
        launch()
        trigger(40, 42, 44)
        rest()
        var readings = stored()
        // Index of the first stray's last actual accepted observation, including release tails.
        val lastFirst = readings.indexOfLast { it.midi == 40 }
        assertTrue(lastFirst >= 0)
        val expirySize = lastFirst + 1 + 435
        assertTrue(readings.size < expirySize)
        val inRange = Sine(frequency(69), 0.1, rate)
        // Eight-hop deliveries require an observer to consume every accepted decision in a batch.
        var iterations = 0
        while (readings.size < expirySize - 1) {
            assertTrue("accepted playing must reach the age boundary", iterations++ < 100)
            feed(inRange, minOf(8, expirySize - 1 - readings.size), batch = 8)
            readings = stored()
        }
        assertEquals(expirySize - 1, readings.size)
        assertEquals(setOf(40, 42, 44, 69), readings.map { it.midi }.toSet())
        hint()
        val before = readings
        feed(inRange, 1)
        readings = stored()
        assertEquals("one fresh accepted in-range reading expires the first stray", expirySize, readings.size)
        assertEquals(before, readings.take(before.size))
        noHint()
        assertEquals(69, graph.tune.state.value.readout.midi)
    }

    @Test fun `HNT-03 one held note repeated attacks and an unconfirmed squeak do not make three strays`() {
        launch()
        tone(40, hops = 220, batch = 8)
        noHint()
        repeat(4) {
            rest(); tone(69); rest(); tone(40)
            noHint()
        }
        rest()
        val beforeSqueak = stored()
        feed(Sine(frequency(100), 0.1, rate), 1)
        rest()
        noHint()
        assertEquals("one unconfirmed hop adds no saved pitch", beforeSqueak, stored())
        assertAccepted(setOf(40, 69))
        val low = runBlocking { graph.history.summaries(Scope.CurrentSession) }.single { it.midiSounding == 40 }
        assertTrue("repeated attacks are still one distinct stray", low.attacks >= 5)
    }

    @Test fun `HNT-03 alto altissimo through written F7 remains in the extended range at 44100`() {
        launch(sampleRate = 44_100)
        for (midi in listOf(86, 89, 92)) {
            tone(midi)
            noHint()
            assertTrue("extended-only note retains normal note ink", ink("readout-letter", GridlineThemes.gridline.textBody) > 0)
            assertTrue("extended-only cents retain tuning ink", ink("readout-cents", GridlineThemes.gridline.positive) > 0)
            rest()
        }
        assertAccepted(setOf(86, 89, 92))
    }

    @Test fun `HNT-04 twenty-five seconds of accepted in-range playing between strays prevents a hint`() {
        launch()
        for ((index, midi) in listOf(40, 42, 44).withIndex()) {
            tone(midi); noHint(); rest()
            if (index < 2) {
                val before = stored().count { it.midi == 69 }
                tone(69, hops = Hop.count(25.0) + 16, batch = 8)
                val added = stored().count { it.midi == 69 } - before
                assertTrue("each gap is at least 25 seconds of accepted in-range readings", added >= Hop.count(25.0))
                noHint(); rest()
            }
        }
        assertAccepted(setOf(40, 42, 44, 69))
    }

    @Test fun `HNT-08 paused capture still triggers and ears first retains its hidden readout`() {
        launch(paused = true)
        trigger(40, 42, 44)
        assertTrue(stored().isEmpty())
        assertTrue(graph.history.status.value.skippedWhilePaused > 0)
        val skipped = graph.history.status.value.skippedWhilePaused
        tap("needle-1")
        tone(44, cents = 4.0)
        hint()
        compose.onNodeWithTag("readout-cents", true).assertDoesNotExist()
        compose.onNodeWithTag("readout-hz", true).assertTextEquals("— Hz")
        assertTrue(graph.history.status.value.skippedWhilePaused > skipped)
        assertTrue(stored().isEmpty())
        tap("needle-0")
        hint()
        compose.onNodeWithTag("readout-cents", true).assertTextEquals("+4")
        tap("capture-0")
        tone(44, cents = 4.0)
        hint()
        assertTrue(stored().isNotEmpty())
        assertEquals(0, graph.history.rejected)
    }

    @Test fun `HNT-07 hint picker Back and selection preserve tools input and historical labels`() {
        launch(tools = true)
        trigger(40, 42, 44)
        val before = stored()
        val commands = output.sent.filterNot { it is OutputCommand.Duck }
        val starts = input.startCalls
        val stops = input.stopCalls
        compose.onNode(action).performClick()
        compose.onNodeWithTag("instrument-bb_tenor").performScrollTo().assertExists()
        tap("back")
        compose.onNodeWithTag("tune").assertExists()
        hint()
        assertEquals(before, stored())
        compose.onNode(action).performClick()
        tap("instrument-bb_tenor")
        compose.onNodeWithTag("tune").assertExists()
        // No PCM is delivered between selection and the removal assertion.
        noHint()
        assertEquals("bb_tenor", graph.prefs.prefs.value.instrumentKey)
        rest(); tone(40); rest(); tone(42)
        noHint()
        assertEquals(before, stored().take(before.size))
        val later = stored().drop(before.size)
        assertTrue(later.isNotEmpty())
        assertTrue(later.all { it.instrument == "bb_tenor" && it.transposition == -14 })
        assertEquals(commands, output.sent.filterNot { it is OutputCommand.Duck })
        assertEquals(setOf(OutputTool.DRONE, OutputTool.METRONOME), output.registered)
        assertTrue(graph.output.state.value.droneSounding && graph.output.state.value.metroSounding)
        assertEquals(57, graph.output.soundingDroneMidi)
        assertEquals(137, graph.prefs.prefs.value.metroBpm)
        assertFalse(graph.prefs.prefs.value.capturePaused)
        assertEquals(starts, input.startCalls)
        assertEquals(stops, input.stopCalls)
        assertTrue(input.isOpen)
    }

    @Test fun `HNT-07 a rapid silent instrument round trip clears the old hint and its distinct notes`() {
        launch()
        trigger(40, 42, 44)
        rest()
        val before = stored()
        // Both publications can reach analysis as one pending final value.
        compose.runOnIdle {
            graph.prefs.update { it.copy(instrumentKey = "bb_tenor") }
            graph.prefs.update { it.copy(instrumentKey = "eb_alto") }
        }
        noHint()
        assertEquals(before, stored())
        tone(40); rest(); tone(42)
        noHint()
        rest(); tone(44)
        hint()
    }

    @Test fun `HNT-07 C Instrument accepts the same low and high PCM without a range hint`() {
        launch(instrument = Catalogue.CATCH_ALL_KEY)
        for (midi in listOf(40, 42, 44, 95, 97, 99)) {
            tone(midi); noHint(); rest()
        }
        val saved = stored()
        assertEquals(setOf(40, 42, 44, 95, 97, 99), saved.map { it.midi }.toSet())
        assertTrue(saved.all { it.instrument == Catalogue.CATCH_ALL_KEY })
    }

    @Test fun `HNT imported effective-range widening removes stale judgments without rewriting history`() {
        launch()
        trigger(40, 42, 44)
        rest()
        val before = stored()
        val document = File(RuntimeEnvironment.getApplication().cacheDir, "hint-range.json")
        document.writeText(PersonalConfigCodec.write(PersonalConfig(rangeOverrides = mapOf(
            "eb_alto" to UserRangeOverride(WrittenRange(45, 101)),
        ))))
        tap("open-settings")
        tap("config-import")
        scenario!!.onActivity { activity ->
            val shadow = shadowOf(activity)
            val request = shadow.nextStartedActivityForResult.intent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.action)
            shadow.receiveResult(request, Activity.RESULT_OK, Intent().setData(Uri.fromFile(document)))
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("config-preview").fetchSemanticsNodes().isNotEmpty() }
        tap("config-apply")
        compose.waitUntil(5_000) { !graph.configuration.state.value.busy && graph.configuration.state.value.incoming == null }
        assertFalse(graph.configuration.state.value.failed)
        assertEquals(WrittenRange(45, 101), graph.prefs.prefs.value.personalConfig.rangeOverrides.getValue("eb_alto").keyed)
        tap("back")
        compose.onNodeWithTag("tune").assertExists()
        noHint()
        assertEquals(before, stored())
        for (midi in listOf(40, 42, 44)) {
            tone(midi); noHint(); rest()
        }
        assertEquals(before, stored().take(before.size))
        assertEquals(1, input.startCalls)
        assertEquals(0, input.stopCalls)
    }
}
