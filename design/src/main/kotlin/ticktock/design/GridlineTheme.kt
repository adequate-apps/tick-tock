// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * Root of every Gridline screen. It paints System Gray behind everything and
 * replaces the default press indication with the relief flip, so no ripple can
 * appear anywhere in the app.
 */
@Composable
fun GridlineTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides PressIndication) {
        Box(Modifier.fillMaxSize().background(GridlineColors.surface)) {
            content()
        }
    }
}
