// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.debug.installDebugInput
import ticktock.app.screens.AppFrame
import ticktock.design.GridlineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prefs load here, before any screen reads them or any reading is stored (SES-01).
        val graph = TickTockApp.graph(this)
        installDebugInput(this, graph)
        setContent {
            val theme by graph.theme.collectAsStateWithLifecycle()
            val palette = paletteOf(theme)
            // The status bar sits on the title strip, which is dark in every
            // theme, so its icons are light; the navigation bar sits on the
            // theme's surface, so its icons follow the theme.
            LaunchedEffect(palette) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    navigationBarStyle = if (palette.isDark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                )
            }
            GridlineTheme(palette) {
                AppFrame()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TickTockApp.graph(this).onForeground()
    }

    override fun onStop() {
        super.onStop()
        TickTockApp.graph(this).onBackground()
    }
}
