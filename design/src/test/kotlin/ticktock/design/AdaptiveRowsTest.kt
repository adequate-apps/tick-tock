// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ticktock.design.components.Button
import ticktock.design.components.GlIcon
import ticktock.design.components.Notice
import ticktock.design.components.NoticeKind
import ticktock.design.components.SettingRow

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h2000dp-hdpi")
class AdaptiveRowsTest(private val fontScale: Float, private val widthDp: Int) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `denied notices keep the message and action on readable lines`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GridlineTheme {
                    Column(Modifier.width(widthDp.dp).testTag("notices")) {
                        Notice("Denied.", kind = NoticeKind.Attention, icon = GlIcon.MIC_SLASH,
                            iconDescription = "Mic denied", tag = "retry") {
                            Button("Allow microphone", {}, tag = "allow")
                        }
                        Notice("Denied.", kind = NoticeKind.Danger, icon = GlIcon.MIC_SLASH,
                            iconDescription = "Mic denied", tag = "denied") {
                            Button("System settings", {}, tag = "settings")
                        }
                    }
                }
            }
        }
        for (tag in listOf("retry-text", "denied-text", "allow", "settings")) {
            for (layout in layouts(tag)) {
                assertEquals("$tag at $fontScale and $widthDp dp", 1, layout.lineCount)
                assertFits(layout)
            }
        }
        if (fontScale == 2f && widthDp == 300) {
            compose.onNodeWithTag("notices").captureRoboImage("src/test/screenshots/denied-notices-large-text.png")
        }
    }

    @Test
    fun `setting rows keep labels whole and long values inside the row`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GridlineTheme {
                    Column(Modifier.width(widthDp.dp).testTag("rows")) {
                        SettingRow("Instrument", "Tenor saxophone", onClick = {}, tag = "tenor")
                        SettingRow("Instrument", "Contrabass clarinet", onClick = {}, tag = "contrabass")
                    }
                }
            }
        }
        for (tag in listOf("tenor", "contrabass")) {
            assertEquals("$tag label at $fontScale and $widthDp dp", 1, layouts("$tag-label").single().lineCount)
            val row = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            val inset = 8 * row.layoutInfo.density.density
            for (part in listOf("label", "value")) {
                layouts("$tag-$part").forEach(::assertFits)
                val text = compose.onNodeWithTag("$tag-$part", useUnmergedTree = true).fetchSemanticsNode()
                assertTrue(text.boundsInRoot.left >= row.boundsInRoot.left + inset)
                assertTrue(text.boundsInRoot.right <= row.boundsInRoot.right - inset)
            }
        }
        if (fontScale == 2f && widthDp == 300) {
            compose.onNodeWithTag("rows").captureRoboImage("src/test/screenshots/settings-rows-large-text.png")
        }
    }

    private fun layouts(tag: String): List<TextLayoutResult> {
        fun collect(node: SemanticsNode): List<TextLayoutResult> {
            val result = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(result)
            node.children.forEach { result.addAll(collect(it)) }
            return result
        }
        return collect(compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()).also {
            assertTrue("$tag has rendered text", it.isNotEmpty())
        }
    }

    private fun assertFits(layout: TextLayoutResult) {
        for (line in 0 until layout.lineCount) {
            val inkWidth = layout.getLineRight(line) - layout.getLineLeft(line)
            assertTrue("${layout.layoutInput.text} overflows", inkWidth <= layout.size.width + 1f)
        }
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fs{0}-w{1}")
        fun cases(): List<Array<Any>> = listOf(1f, 1.3f, 1.5f, 2f).flatMap { scale ->
            listOf(300, 560).map { width -> arrayOf<Any>(scale, width) }
        }
    }
}
