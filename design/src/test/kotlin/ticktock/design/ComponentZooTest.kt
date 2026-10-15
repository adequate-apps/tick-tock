// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.design.components.Button
import ticktock.design.components.ButtonTone
import ticktock.design.components.Caption
import ticktock.design.components.Field
import ticktock.design.components.GlIcon
import ticktock.design.components.Icon
import ticktock.design.components.Stepper
import ticktock.design.plots.CentsScale
import ticktock.design.plots.LevelMeter
import ticktock.design.plots.Trace
import ticktock.design.components.GlText
import ticktock.design.components.Notice
import ticktock.design.components.NoticeKind
import ticktock.design.components.Pane
import ticktock.design.components.Segment
import ticktock.design.components.Segmented
import ticktock.design.components.SettingRow
import ticktock.design.components.StatusBar
import ticktock.design.components.TitleBar
import ticktock.design.components.TitleBarAction

/**
 * The component consistency gate (PLAN section 1). Every Gridline component,
 * in its states, with short and long labels, rendered in every theme at four
 * font scales and at phone and tablet widths. Each case asserts what a
 * screenshot cannot be trusted to show a reviewer:
 * - no single-line label wraps, clips or ellipsizes, and every label keeps at
 *   least 8 dp to the edge of its key (label fit);
 * - every interactive element is at least 48 dp each way;
 * - every interactive element carries a label.
 * The golden for each case is the picture the design owner signs off, and
 * `verifyRoborazziDebug` fails on any pixel change.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h4000dp-hdpi")
class ComponentZooTest(private val theme: GridlinePalette, private val fontScale: Float, private val widthDp: Int) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `labels fit, targets are 48 dp and every control is labelled`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GridlineTheme(theme) {
                    Column(Modifier.width(widthDp.dp).testTag(ZOO)) { Zoo() }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.onNodeWithTag(ZOO).fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        val controls = root.descendants().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
        val problems = mutableListOf<String>()

        for (control in controls) {
            val name = control.label()
            val w = control.boundsInRoot.width / density
            val h = control.boundsInRoot.height / density
            if (w < TARGET_DP || h < TARGET_DP) problems += "'$name' is ${w.toInt()}x${h.toInt()} dp, under $TARGET_DP"
            if (name.isBlank()) problems += "a control at ${control.boundsInRoot} has no label"
        }

        val unmerged = compose.onNodeWithTag(ZOO, useUnmergedTree = true).fetchSemanticsNode()
        for (text in unmerged.descendants().filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }) {
            val layout = text.layout() ?: continue
            val words = text.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
            val key = text.enclosingControl()
            val singleLine = layout.layoutInput.maxLines == 1 || key != null
            // A second line or ink wider than its box is a label that does not fit.
            // The line's ink, from its left to its right edge, against the box the
            // text was given. A pixel of slack absorbs rounding.
            val inkLeft = layout.getLineLeft(0)
            val inkRight = layout.getLineRight(0)
            val overshoot = (inkRight - inkLeft) - layout.size.width
            // A one-line limit cuts a long label at a word with no ellipsis; the
            // last drawn character then falls short of the end of the text.
            val cut = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) < layout.layoutInput.text.length
            if (singleLine && (layout.lineCount > 1 || cut || overshoot > 1f || layout.isLineEllipsized(0))) {
                problems += "'$words' wraps or clips (${layout.lineCount} lines, ${"%.1f".format(overshoot)} px over)"
            }
            if (key != null) {
                // Line positions are relative to the paragraph, which centred or
                // end-aligned text lays out wider than its box; place the ink by
                // the alignment inside the box the text actually occupies.
                val box = text.boundsInRoot
                val inkWidth = inkRight - inkLeft
                val left = when (layout.layoutInput.style.textAlign) {
                    TextAlign.Center -> box.center.x - inkWidth / 2
                    TextAlign.End, TextAlign.Right -> box.right - inkWidth
                    else -> box.left
                }
                val ink = Rect(left, box.top, left + inkWidth, box.bottom)
                val inset = insetDp(key.boundsInRoot, ink, density)
                if (inset < PADDING_DP) problems += "'$words' sits ${"%.1f".format(inset)} dp from the edge of its key, under $PADDING_DP"
            }
        }

        // The title strip's state either keeps its 16 dp to the title and the action, or gives way.
        val state = compose.onAllNodesWithTag("titlebar-state", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        if (state != null) {
            val action = compose.onNodeWithTag("zoo-settings").fetchSemanticsNode()
            val gap = (action.boundsInRoot.left - state.boundsInRoot.right) / density
            if (gap < STRIP_GAP_DP) problems += "the title strip's state sits ${"%.1f".format(gap)} dp from its action, under $STRIP_GAP_DP"
            val title = compose.onNodeWithTag("titlebar-title", useUnmergedTree = true).fetchSemanticsNode()
            val titleGap = (state.boundsInRoot.left - title.boundsInRoot.right) / density
            if (titleGap < STRIP_GAP_DP) problems += "the title strip's state sits ${"%.1f".format(titleGap)} dp from its title, under $STRIP_GAP_DP"
        }

        assertEquals("${theme.name} at font scale $fontScale, $widthDp dp wide", emptyList<String>(), problems)
        compose.onNodeWithTag(ZOO).captureRoboImage("src/test/screenshots/zoo-${theme.name.lowercase()}-w$widthDp-fs$fontScale.png")
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    private fun SemanticsNode.label(): String =
        (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
            .joinToString(" ")

    private fun SemanticsNode.layout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()
    }

    /** The nearest ancestor that takes a tap: the key this text labels. */
    private fun SemanticsNode.enclosingControl(): SemanticsNode? {
        var node = parent
        while (node != null) {
            if (node.config.getOrNull(SemanticsActions.OnClick) != null) return node
            node = node.parent
        }
        return null
    }

    private fun insetDp(outer: Rect, inner: Rect, density: Float): Float =
        minOf(inner.left - outer.left, outer.right - inner.right) / density

    companion object {
        private const val ZOO = "zoo"
        private const val TARGET_DP = 48f
        private const val PADDING_DP = 8f
        private const val STRIP_GAP_DP = 16f

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} fs{1} w{2}")
        fun cases(): List<Array<Any>> =
            GridlineThemes.all.flatMap { theme ->
                listOf(1.0f, 1.3f, 1.5f, 2.0f).flatMap { scale -> listOf(360, 600).map { width -> arrayOf<Any>(theme, scale, width) } }
            }
    }
}

/** Every component, the way screens use them. Labels are real ones from the mockups, short and long. */
@Composable
private fun Zoo() {
    val gap = Modifier.padding(GridlineSpacing.sp2)
    TitleBar(title = "Metronome", state = "Idle") {
        TitleBarAction(label = "Settings", onClick = {}, tag = "zoo-settings")
    }
    Segmented(
        segments = listOf(Segment("Tune"), Segment("Drone", running = true), Segment("Metronome"), Segment("Table")),
        selectedIndex = 0,
        onSelect = {},
        tag = "zoo-tabs",
    )
    Column(gap, verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        Pane(title = "Theme", modifier = Modifier.fillMaxWidth(), state = "Pit") {
            Segmented(
                segments = listOf(Segment("Gridline"), Segment("Daylight"), Segment("Dark"), Segment("Pit")),
                selectedIndex = 3,
                onSelect = {},
                tag = "zoo-themes",
            )
        }
        Pane(title = "Tune", modifier = Modifier.fillMaxWidth(), state = "Listening") {
            Field(Modifier.fillMaxWidth()) {
                GlText(text = "A4", style = GridlineType.measure)
                GlText(text = "+3 c", style = GridlineType.value, color = GridlineColors.positive)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            Button(label = "Tap", onClick = {}, tag = "zoo-tap")
            Button(label = "Save", onClick = {}, enabled = false, tag = "zoo-save")
        }
        Button(label = "Start metronome", onClick = {}, tone = ButtonTone.Primary, tag = "zoo-start")
        Caption("Drone")
        // Rows form one list inside a field, as on the Settings screen.
        Column(Modifier.fillMaxWidth().frame()) {
            SettingRow(label = "A4", value = "440 Hz", onClick = {}, tag = "zoo-a4")
            SettingRow(label = "Noise floor", value = "-45 dB", tag = "zoo-floor")
        }
        CentsScale(cents = 4, description = "+4", modifier = Modifier.fillMaxWidth(), tag = "zoo-scale")
        CentsScale(cents = null, modifier = Modifier.fillMaxWidth(), tag = "zoo-scale-ghost")
        LevelMeter(db = { -18.0 }, caption = "Level", tag = "zoo-level")
        Trace(
            points = { FloatArray(108) { i -> if (i < 10) Float.NaN else 14f * kotlin.math.exp(-(i - 10) / 12f) + 1f } },
            timeLabels = listOf(0f to "−5 s", 0.6f to "−2 s", 1f to "now"),
            description = "Pitch trace, last five seconds",
            modifier = Modifier.fillMaxWidth(),
            tag = "zoo-trace",
        )
        Stepper(value = "440 Hz", onLower = {}, onRaise = {}, lowerLabel = "Lower A4", raiseLabel = "Raise A4", modifier = Modifier.fillMaxWidth(), tag = "zoo-a4")
        Row(horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            GlIcon.entries.forEach { Icon(it) }
        }
        Notice(text = "", kind = NoticeKind.Plain, icon = GlIcon.MIC_SLASH, tag = "zoo-mic") {
            Button(label = "Allow microphone", onClick = {}, tone = ButtonTone.Primary, tag = "zoo-allow")
        }
        Notice(text = "Held by another app", kind = NoticeKind.Danger, tag = "zoo-busy") {
            Button(label = "Try again", onClick = {}, tag = "zoo-retry")
        }
        Notice(text = "Click lands late on Bluetooth", kind = NoticeKind.Attention, tag = "zoo-bluetooth") {
            Button(label = "Measure", onClick = {}, tag = "zoo-measure")
        }
        Notice(text = "Copy of old settings kept", kind = NoticeKind.Plain, tag = "zoo-copy")
    }
    StatusBar(segments = listOf("Listening", "Bluetooth"))
}
