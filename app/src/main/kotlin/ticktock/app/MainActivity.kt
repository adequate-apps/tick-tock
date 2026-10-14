// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ticktock.app.screens.AppFrame
import ticktock.design.GridlineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The status bar sits on the Active Blue title strip, so its icons are
        // light; the navigation bar sits on System Gray, so its icons are dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            GridlineTheme {
                AppFrame()
            }
        }
    }
}
