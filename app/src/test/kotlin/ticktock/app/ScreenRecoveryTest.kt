// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
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
import ticktock.app.output.TestOutputConnection
import ticktock.app.screens.ScreenRenderProbe
import ticktock.core.contracts.OutputTool
import ticktock.design.GridlineSpacing
import ticktock.design.components.SquareDialog
import ticktock.design.components.TextField
import ticktock.data.Storage
import ticktock.data.history.StoreStatus
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class ScreenRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun close() {
        ScreenRenderProbe.beforeRender = null
        ScreenRenderProbe.content = null
        scenario?.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun `ERR-01 a real screen composition failure leaves navigation and sounding tools intact`() {
        val context = RuntimeEnvironment.getApplication()
        val graph = TickTockApp.graph(context)
        val link = TestOutputConnection()
        graph.tune.micAllowed = { false }
        graph.output.makeConnection = { link }
        graph.prefs.update { it.copy(firstRunComplete = true, metroBpm = 137, droneMidi = 57) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.runOnIdle { graph.output.startDrone(); graph.output.startMetro() }
        val commandsBefore = link.sent.toList()
        var throws = 0
        ScreenRenderProbe.beforeRender = { screen ->
            if (screen == "metronome") {
                throws++
                throw IllegalStateException("Planted screen render failure")
            }
        }

        compose.onNodeWithTag("tabs-2").performClick()
        compose.onNodeWithTag("screen-failure-metronome").assertIsDisplayed()
        compose.onNodeWithTag("screen-share-log").assertTextEquals("Share log").assertHasClickAction()
        assertEquals("the failed composition is not retried automatically", 1, throws)
        assertSame(graph, TickTockApp.graph(context))
        assertEquals(setOf(OutputTool.DRONE, OutputTool.METRONOME), link.registered)
        assertTrue(graph.output.state.value.droneSounding)
        assertTrue(graph.output.state.value.metroSounding)
        assertEquals(commandsBefore, link.sent)

        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("drone-toggle").assertIsDisplayed().assertTextEquals("Stop")
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-ears-first-0").performScrollTo().performClick()
        assertTrue(graph.prefs.prefs.value.earsFirst)
        assertEquals(137, graph.prefs.prefs.value.metroBpm)
        assertEquals(57, graph.prefs.prefs.value.droneMidi)
        assertEquals(commandsBefore, link.sent)
    }

    private fun launchSounding(): Pair<AppGraph, TestOutputConnection> {
        val graph = TickTockApp.graph(RuntimeEnvironment.getApplication())
        val link = TestOutputConnection()
        graph.tune.micAllowed = { false }
        graph.output.makeConnection = { link }
        graph.prefs.update { it.copy(firstRunComplete = true, metroBpm = 137, droneMidi = 57) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.runOnIdle { graph.output.startDrone(); graph.output.startMetro() }
        return graph to link
    }

    private fun assertSurvived(graph: AppGraph, link: TestOutputConnection) {
        compose.onNodeWithTag("screen-failure-metronome").assertIsDisplayed()
        compose.onNodeWithTag("screen-share-log").assertHasClickAction()
        assertEquals(setOf(OutputTool.DRONE, OutputTool.METRONOME), link.registered)
        assertTrue(graph.output.state.value.droneSounding && graph.output.state.value.metroSounding)
        compose.onNodeWithTag("tabs-1").performClick()
        compose.onNodeWithTag("drone-toggle").assertIsDisplayed().assertTextEquals("Stop")
    }

    @Test fun `ERR-01 a later recomposition failure is isolated and Retry uses a fresh composition`() {
        val (graph, link) = launchSounding()
        val poison = mutableStateOf(false)
        var attempts = 0
        ScreenRenderProbe.beforeRender = { screen ->
            if (screen == "metronome" && poison.value) {
                attempts++
                throw IllegalStateException("Planted later render failure")
            }
        }
        compose.onNodeWithTag("tabs-2").performClick()
        compose.onNodeWithTag("metro-bpm").assertExists()
        compose.runOnIdle { poison.value = true }
        assertSurvived(graph, link)
        compose.onNodeWithTag("tabs-2").performClick()
        assertEquals(1, attempts)
        compose.runOnIdle { poison.value = false }
        compose.onNodeWithTag("screen-retry").performClick()
        compose.onNodeWithTag("metro-bpm").assertExists()
        compose.onNodeWithTag("screen-failure-metronome").assertDoesNotExist()
    }

    @Test fun `ERR-01 a failed pane survives recreation without repeating its render fault`() {
        val (graph, link) = launchSounding()
        var attempts = 0
        ScreenRenderProbe.beforeRender = { screen -> if (screen == "metronome") {
            attempts++
            throw IllegalStateException("Planted recreation render failure")
        } }
        compose.onNodeWithTag("tabs-2").performClick()
        compose.onNodeWithTag("screen-failure-metronome").assertIsDisplayed()
        scenario!!.recreate()
        assertSurvived(graph, link)
        assertEquals(1, attempts)
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun `ERR-01 a temporarily detached pane returns with its saved draft and sounding tools`() {
        val (graph, link) = launchSounding()
        ScreenRenderProbe.content = { screen -> if (screen == "metronome") {
            var draft by rememberSaveable { mutableStateOf("") }
            TextField("Draft", draft, { draft = it }, tag = "recovery-draft")
        } }
        compose.onNodeWithTag("tabs-2").performClick()
        compose.onNodeWithTag("recovery-draft").performScrollTo().performTextInput("Kept draft")
        val commandsBefore = link.sent.toList()
        scenario!!.onActivity { activity ->
            fun host(view: View): View? {
                if (view.javaClass.simpleName == "GuardedContentView") return view
                if (view is ViewGroup) repeat(view.childCount) { index -> host(view.getChildAt(index))?.let { return it } }
                return null
            }
            val pane = checkNotNull(host(activity.window.decorView))
            fun roots(view: View): List<ViewRootForTest> = buildList {
                if (view is ViewRootForTest) add(view)
                if (view is ViewGroup) repeat(view.childCount) { addAll(roots(view.getChildAt(it))) }
            }
            val oldRoots = roots(pane)
            val parent = pane.parent as ViewGroup
            val index = parent.indexOfChild(pane)
            val layout = pane.layoutParams
            parent.removeView(pane)
            parent.addView(pane, index, layout)
            // Robolectric does not draw windows. Exercise a bounded number of real
            // traversals after the posted child recreation, without waiting on Compose
            // idleness first (the new root may itself be waiting for this traversal).
            repeat(3) {
                compose.mainClock.advanceTimeByFrame()
                shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                val window = activity.window.decorView
                fun force(view: View) {
                    view.forceLayout()
                    if (view is ViewGroup) repeat(view.childCount) { force(view.getChildAt(it)) }
                }
                force(window)
                val width = window.width.coerceAtLeast(1)
                val height = window.height.coerceAtLeast(1)
                window.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                window.layout(window.left, window.top, window.left + width, window.top + height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try { window.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            }
            assertTrue("previous child roots detached", oldRoots.all { !it.view.isAttachedToWindow })
            assertTrue("replacement child root attached", roots(pane).any { it.view.isAttachedToWindow })
            assertFalse("attached roots settled after three window traversals",
                roots(activity.window.decorView).any { it.hasPendingMeasureOrLayout })
            assertFalse("Compose settled after bounded traversal", compose.hasPendingWork())
        }
        compose.onNodeWithTag("recovery-draft").performScrollTo().assertTextContains("Kept draft")
        compose.onNodeWithTag("metro-bpm").performScrollTo().assertExists()
        assertEquals(commandsBefore, link.sent)
        assertTrue(graph.output.state.value.droneSounding && graph.output.state.value.metroSounding)
    }

    private fun launchCrowdedNotices(): AppGraph {
        RuntimeEnvironment.setFontScale(2f)
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, Storage.PREFS_FILE).writeText("{ broken")
        val graph = TickTockApp.graph(context)
        graph.tune.micAllowed = { false }
        graph.crashLog.record(Thread.currentThread(), IllegalStateException("Planted earlier crash"), "")
        (graph.history.status as MutableStateFlow<StoreStatus>).value = StoreStatus(failing = true)
        graph.output.makeConnection = { TestOutputConnection().apply {
            openInfo = TestOutputConnection.INFO.copy(wireless = true, deviceId = 7, deviceName = "Headset")
        } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.runOnIdle { graph.output.startDrone(); graph.output.startMetro() }
        return graph
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun `ERR-01 crowded notices and screen controls remain scrollable at double text`() {
        launchCrowdedNotices()
        compose.onNodeWithTag("notice-key-1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("notice-prefs-dismiss").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("bluetooth-measure").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("settings-about").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("notice-key-2").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun `ERR-01 crowded notices leave recovery Share and Retry reachable at double text`() {
        val graph = launchCrowdedNotices()
        ScreenRenderProbe.beforeRender = { screen -> if (screen == "metronome") {
            throw IllegalStateException("Planted crowded render failure")
        } }
        compose.onNodeWithTag("tabs-2").performClick()
        compose.onNodeWithTag("screen-share-log").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("screen-retry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("notice-prefs-dismiss").performScrollTo().assertIsDisplayed()
        assertTrue(graph.output.state.value.droneSounding && graph.output.state.value.metroSounding)
    }

    @Test fun `ERR-01 measurement failure stays inside the screen`() = assertPhase("measure")
    @Test fun `ERR-01 placement failure stays inside the screen`() = assertPhase("place")
    @Test fun `ERR-01 drawing failure stays inside the screen`() = assertPhase("draw")
    @Test fun `ERR-01 apply side effect failure stays inside the screen`() = assertPhase("side-effect")
    @Test fun `ERR-01 coroutine effect failure stays inside the screen`() = assertPhase("effect")
    @Test fun `ERR-01 dialog composition failure closes only its screen`() = assertPhase("dialog-compose")
    @Test fun `ERR-01 dialog measurement failure closes only its screen`() = assertPhase("dialog-measure")
    @Test fun `ERR-01 dialog drawing failure closes only its screen`() = assertPhase("dialog-draw")

    private fun assertPhase(phase: String) {
        val (graph, link) = launchSounding()
        val commandsBefore = link.sent.toList()
        val poison = mutableStateOf(false)
        var traversalRoot: View? = null
        var injected = 0
        val fault = { injected++; throw IllegalStateException("Planted $phase failure") }
        ScreenRenderProbe.content = { screen -> if (screen == "metronome") {
            when (phase) {
                "measure", "place", "draw" -> TraversalFault(phase, poison, fault) { traversalRoot = it }
                "side-effect" -> SideEffect { fault() }
                "effect" -> LaunchedEffect(Unit) { fault() }
                else -> SquareDialog("Fault probe", {}, tag = "fault-dialog") {
                    when (phase) {
                        "dialog-compose" -> fault()
                        "dialog-measure", "dialog-draw" -> TraversalFault(phase.removePrefix("dialog-"), poison, fault) { traversalRoot = it }
                    }
                }
            }
        } }
        compose.onNodeWithTag("tabs-2").performClick()
        if (phase in listOf("measure", "place", "draw", "dialog-measure", "dialog-draw")) {
            compose.waitForIdle()
            scenario!!.onActivity {
                val root = checkNotNull(traversalRoot)
                poison.value = true
                Snapshot.sendApplyNotifications()
                fun force(view: View) {
                    view.forceLayout()
                    if (view is ViewGroup) repeat(view.childCount) { force(view.getChildAt(it)) }
                }
                force(root)
                val width = root.width.coerceAtLeast(1)
                val height = root.height.coerceAtLeast(1)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(root.left, root.top, root.left + width, root.top + height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try { root.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            }
        }
        assertSurvived(graph, link)
        assertTrue("the actual $phase fault ran", injected > 0)
        assertEquals(commandsBefore, link.sent)
        compose.onNodeWithTag("fault-dialog").assertDoesNotExist()
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Composable
    private fun TraversalFault(phase: String, poison: State<Boolean>, fault: () -> Nothing, root: (View) -> Unit) {
        val view = LocalView.current
        SideEffect {
            // The test harness normally intercepts layout errors inside AndroidComposeView,
            // before an enclosing ViewGroup can catch them. Restore production propagation
            // for this poisoned root only, then exercise the real View traversal below.
            (view as RootForTest).setUncaughtExceptionHandler(null)
            root(view.rootView)
        }
        val modifier = when (phase) {
            "measure" -> Modifier.layout { measurable, constraints ->
                if (poison.value) fault()
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.place(0, 0) }
            }
            "place" -> Modifier.layout { measurable, constraints ->
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { if (poison.value) fault(); child.place(0, 0) }
            }
            else -> Modifier.drawBehind { if (poison.value) fault() }
        }
        Box(Modifier.size(GridlineSpacing.sp3).then(modifier))
    }
}
