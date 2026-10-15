// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.app.output.TestOutputConnection
import ticktock.core.detect.Reading
import ticktock.core.prefs.Prefs
import ticktock.data.history.Scope
import ticktock.data.history.SessionPurpose
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class TableScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var graph: AppGraph
    private var hop = 0L
    private var saved = 0L
    @After fun close() { scenario?.close() }

    private fun launch() {
        graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        graph.tune.micAllowed = { false }
        graph.output.makeConnection = { TestOutputConnection() }
        graph.prefs.update { it.copy(firstRunComplete = true, instrumentKey = "eb_alto", a4Hz = 442.0, lastEquipment = "Reed 3") }
        runBlocking {
            add(69, 4.0, 38)
            saved = graph.history.currentSessionId()!!
            graph.history.saveSession(saved, "Earlier", "Reed 2")
            graph.history.newSession()
            add(71, -8.0, 8)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("tabs-3").performClick()
        awaitRows(71)
    }
    private suspend fun add(midi: Int, cents: Double, count: Int) {
        repeat(count) { index ->
            graph.history.accept(Reading(hop++, midi, 442.0 * 2.0.pow((midi - 69 + cents / 100) / 12), cents, 442.0, 0), index == 0)
        }
        graph.history.flushAndWait()
    }
    private fun awaitRows(vararg midi: Int) = compose.waitUntil(5_000) {
        midi.all { compose.onAllNodesWithTag("table-note-$it").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }

    private fun awaitState(predicate: () -> Boolean) {
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            predicate()
        }
    }

    @Test fun `SES-03 table scope and note clear retain saved history and historical A4`() {
        launch()
        compose.onNodeWithTag("table-note-71").assertContentDescriptionEquals("A flat 5, mean minus 8 cents, spread 0 cents, 1 attack, 8 readings")
        compose.onAllNodesWithTag("table-note-69").assertCountEquals(0)
        click("table-scope-1")
        awaitRows(69, 71)
        click("table-scope-0")
        awaitRows(71)
        compose.runOnIdle { graph.prefs.update { it.copy(a4Hz = 466.0) } }
        click("table-note-71")
        compose.onNodeWithText("A4 442 Hz", substring = true).assertExists()
        click("table-hear")
        compose.runOnIdle {
            assertTrue(graph.output.state.value.droneWanted)
            assertEquals(71, graph.output.selectedMidi())
            assertEquals(442.0, graph.prefs.prefs.value.a4Hz, 0.0)
        }
        click("table-hear")
        assertFalse(graph.output.state.value.droneWanted)
        click("table-clear-note")
        click("clear-cancel")
        assertEquals(8L, runBlocking { graph.history.summaries(Scope.CurrentSession).sumOf { it.n } })
        click("table-clear-note")
        click("clear-confirm")
        awaitState { !graph.table.state.value.busy && graph.table.state.value.snapshot?.rows?.isEmpty() == true }
        assertEquals(38L, runBlocking { graph.history.summaries(Scope.Session(saved)).sumOf { it.n } })
    }

    @Test fun `EQP-02 saved session metadata round trips through the sheet and named actions`() {
        launch()
        click("table-save")
        compose.onNodeWithTag("session-equipment").assertTextEquals("Reed 3")
        compose.onNodeWithTag("session-name").performTextInput("Practice room")
        compose.onNodeWithTag("session-purpose-1").performScrollTo().performClick()
        compose.onNodeWithTag("session-notes").performScrollTo().performTextInput("Warm horn\nSoft reed")
        scenario!!.recreate()
        compose.onNodeWithTag("session-name").assertTextEquals("Practice room")
        compose.onNodeWithTag("session-equipment").assertTextEquals("Reed 3")
        compose.onNodeWithTag("session-purpose-1").assertIsSelected()
        compose.onNodeWithTag("session-notes").assertTextEquals("Warm horn\nSoft reed")
        click("session-save")
        awaitState { graph.table.state.value.snapshot?.sessions?.any { it.name == "Practice room" } == true }
        val savedNow = graph.table.state.value.snapshot!!.sessions.single { it.name == "Practice room" }
        assertEquals(SessionPurpose.SHOP, savedNow.purpose)
        assertEquals("Warm horn\nSoft reed", savedNow.notes)
        assertEquals("Reed 3", savedNow.equipment)
        click("table-new")
        awaitState { graph.table.state.value.snapshot?.currentSessionId == null && !graph.table.state.value.busy }
        click("table-sessions")
        click("session-resume-$saved")
        awaitRows(69)
        assertEquals(saved, graph.table.state.value.snapshot!!.currentSessionId)
        click("table-clear")
        click("clear-cancel")
        assertTrue(runBlocking { graph.history.sessions() }.any { it.id == saved })
        click("table-clear")
        click("clear-confirm")
        awaitState { graph.table.state.value.snapshot?.sessions?.none { it.id == saved } == true }
        assertTrue(runBlocking { graph.history.sessions() }.any { it.id == savedNow.id })
    }
    @Test fun `SES-07 a refused session save keeps its draft and shows the storage error in the sheet`() {
        launch()
        fun sql(statement: String) = graph.storage.call {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                RuntimeEnvironment.getApplication().getDatabasePath(ticktock.data.Storage.DATABASE_FILE).path,
                null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(statement) }
        }
        sql("CREATE TRIGGER refuse_session_save BEFORE UPDATE OF name ON sessions BEGIN SELECT RAISE(ABORT, 'write refused'); END")
        click("table-save")
        compose.onNodeWithTag("session-name").performTextInput("Retained draft")
        click("session-save")
        awaitState { graph.table.state.value.error != null && !graph.table.state.value.busy }
        compose.onNode(hasText("Phone storage refused save") and hasAnyAncestor(hasTestTag("save-session-dialog")))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("session-name").assertTextEquals("Retained draft")
        scenario!!.recreate()
        compose.onNodeWithTag("session-name").assertTextEquals("Retained draft")
        compose.onNode(hasText("Phone storage refused save") and hasAnyAncestor(hasTestTag("save-session-dialog"))).assertExists()
        sql("DROP TRIGGER refuse_session_save")
        click("session-save")
        awaitState { graph.table.state.value.snapshot?.sessions?.any { it.name == "Retained draft" } == true }
        compose.onNodeWithTag("save-session-dialog").assertDoesNotExist()
    }

    @Test fun `histograms label scope units density and both comparison statistics`() {
        launch()
        runBlocking { add(69, -10.0, 8) }
        awaitRows(69)
        compose.onNodeWithText("Mean · cents").assertExists()
        compose.onNodeWithText("Plots −25 to +25 cents").assertExists()
        compose.onNodeWithText("Dimmed <5 readings").assertExists()
        click("table-note-69")
        compose.onAllNodesWithText("Session", substring = false).assertCountEquals(2)
        compose.onNodeWithText("Share of readings · −50 to +50 cents").assertExists()
        compose.onNode(hasTestTag("table-stats-selected") and hasAnyDescendant(hasText("Mean -10 cents · Spread 0 cents"))).assertExists()
        compose.onNode(hasTestTag("table-stats-history") and hasAnyDescendant(hasText("Readings 46", substring = true))).assertExists()
        compose.onNode(hasTestTag("table-stats-history") and hasAnyDescendant(hasText("Mean +2 cents", substring = true))).assertExists()
        compose.onNodeWithText("Scope since", substring = true).assertExists()
    }

    @Test fun `note clearing identifies current saved and all history scopes and Cancel is primary`() {
        launch()
        fun confirm(scope: String) {
            click("table-clear-note")
            compose.onNode(hasText(scope) and hasAnyAncestor(hasTestTag("table-confirmation"))).assertExists()
            compose.onNode(hasText("A4 442 Hz", substring = true) and hasAnyAncestor(hasTestTag("table-confirmation"))).assertExists()
            val pixels = compose.onNodeWithTag("clear-cancel").performScrollTo().captureToImage().toPixelMap()
            assertEquals(ticktock.design.GridlineThemes.gridline.selectionBg, pixels[pixels.width / 2, 8])
            click("clear-cancel")
            click("table-back")
        }
        click("table-note-71")
        confirm("Current session")
        click("table-sessions")
        click("session-open-$saved")
        awaitRows(69)
        click("table-note-69")
        confirm("Earlier")
        click("table-scope-1")
        awaitRows(69, 71)
        click("table-note-69")
        compose.onNodeWithText("History · Drone runs excluded").assertExists()
        click("table-clear-note")
        compose.onNode(hasText("All matching history") and hasAnyAncestor(hasTestTag("table-confirmation"))).assertExists()
        compose.onNode(hasText("Drone runs excluded") and hasAnyAncestor(hasTestTag("table-confirmation"))).assertExists()
        click("clear-cancel")
        assertEquals(38L, runBlocking { graph.history.summaries(Scope.Session(saved)).sumOf { it.n } })
    }

}
