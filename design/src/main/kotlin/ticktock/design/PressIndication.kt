// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * Gridline's only press feedback: the raised bevel flips to pressed while the
 * finger is down. No ripple, no tint, no animation beyond the swap. Installed
 * by [GridlineTheme] as the default indication, so any clickable that does
 * not draw its own bevel still answers a touch the Gridline way.
 */
object PressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()

    private class PressNode(private val interactionSource: InteractionSource) :
        Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> pressed = true
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            // The palette is read at draw time, so a theme change re-draws every face in the new colours.
            val relief = currentValueOf(LocalGridlinePalette).relief
            val bevel = if (pressed) relief.reliefPressed else relief.reliefRaised
            bevelLines(GridlineMetrics.line.toPx(), bevel.topLeft, bevel.bottomRight)
        }
    }
}
