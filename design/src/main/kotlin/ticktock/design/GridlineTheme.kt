// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** The palette in force; [GridlineTheme] provides it. */
val LocalGridlinePalette = staticCompositionLocalOf { GridlineThemes.gridline }

/** The current theme's colours, by role. Components read colours here and nowhere else. */
val GridlineColors: GridlinePalette
    @Composable @ReadOnlyComposable
    get() = LocalGridlinePalette.current

/** The current theme's bevels and frames. */
val GridlineRelief: GridlineReliefSet
    @Composable @ReadOnlyComposable
    get() = LocalGridlinePalette.current.relief

/**
 * Root of every Gridline screen. It provides [palette], paints the theme's
 * surface behind everything and replaces the default press indication with
 * the relief flip, so no ripple can appear anywhere in the app.
 */
@Composable
fun GridlineTheme(palette: GridlinePalette = GridlineThemes.gridline, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGridlinePalette provides palette, LocalIndication provides PressIndication) {
        Box(Modifier.fillMaxSize().background(palette.surface)) {
            content()
        }
    }
}
