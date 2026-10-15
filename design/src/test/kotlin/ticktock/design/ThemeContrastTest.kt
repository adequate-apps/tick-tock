// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every text role against every surface it is drawn on, in every theme. A
 * pair under its threshold is a design defect: change the token, never the
 * threshold. `borderLight` is left out on purpose: it is the rule between
 * rows of a list, a divider the row's own spacing already makes.
 */
class ThemeContrastTest {
    private fun pairs(p: GridlinePalette): List<Triple<String, Pair<Color, Color>, Double>> {
        val text = Contrast.TEXT
        val ui = Contrast.LARGE_OR_UI
        return listOf(
            Triple("textBody on surface", p.textBody to p.surface, text),
            Triple("textBody on surface2", p.textBody to p.surface2, text),
            Triple("textBody on controlFace", p.textBody to p.controlFace, text),
            Triple("textBody on field", p.textBody to p.field, text),
            Triple("textSecondary on surface", p.textSecondary to p.surface, text),
            Triple("textSecondary on field", p.textSecondary to p.field, text),
            Triple("textSecondary on controlFace", p.textSecondary to p.controlFace, text),
            Triple("titleFg on titleBg", p.titleFg to p.titleBg, text),
            Triple("selectionFg on selectionBg", p.selectionFg to p.selectionBg, text),
            Triple("accentText on surface", p.accentText to p.surface, text),
            Triple("accentText on field", p.accentText to p.field, text),
            Triple("textBody on yellowBg", p.textBody to p.yellowBg, text),
            Triple("textBody on redBg", p.textBody to p.redBg, text),
            // Edges and marks that carry meaning without words beside them.
            Triple("borderStrong on surface", p.borderStrong to p.surface, ui),
            // A key's edge: in Pit, where the bevels are black, it is the only sign of a key.
            Triple("border on surface", p.border to p.surface, ui),
            Triple("border on controlFace", p.border to p.controlFace, ui),
            Triple("selectionOutline on surface", p.selectionOutline to p.surface, ui),
            Triple("positive on field", p.positive to p.field, ui),
            Triple("attention on field", p.attention to p.field, ui),
            Triple("danger on field", p.danger to p.field, ui),
            // The live square is cyan inside an ink edge; the edge is what must stand out from the key.
            Triple("live edge on controlFace", p.borderStrong to p.controlFace, ui),
            Triple("live edge on selectionBg", p.selectionFg to p.selectionBg, ui),
        )
    }

    @Test
    fun `every text and mark clears its contrast threshold in all four themes`() {
        val failures = GridlineThemes.all.flatMap { palette ->
            pairs(palette).mapNotNull { (what, colours, min) ->
                val ratio = Contrast.ratio(colours.first, colours.second)
                if (ratio < min) "${palette.name}: $what is ${"%.2f".format(ratio)}:1, needs $min:1" else null
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `the contrast formula matches known values`() {
        assertEquals(21.0, Contrast.ratio(Color.Black, Color.White), 1e-9)
        assertEquals(1.0, Contrast.ratio(Color.Red, Color.Red), 1e-9)
        assertEquals(4.54, Contrast.ratio(Color(0xFF767676), Color.White), 0.01)
    }
}
