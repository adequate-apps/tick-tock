// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ticktock.app.R
import ticktock.design.GridlineSpacing
import ticktock.design.components.Button
import ticktock.design.components.GuardedContent
import ticktock.design.components.Notice
import ticktock.design.components.NoticeKind

/** Screen incidents contain class/frame identities only, never exception messages or user values. */
internal class ScreenFailures(initial: Map<String, String> = emptyMap()) {
    val entries = mutableStateMapOf<String, String>().apply { putAll(initial) }
    fun record(screen: String, failure: Throwable) {
        if (screen in entries) return
        val entry = buildString {
            append("Screen ").append(screen).append('\n')
            append(failure.javaClass.name).append('\n')
            failure.stackTrace.take(24).forEach { frame ->
                append(frame.className).append('.').append(frame.methodName).append(':').append(frame.lineNumber).append('\n')
            }
        }.take(4096)
        entries[screen] = entry
        android.util.Log.e("ScreenRecovery", entry)
    }
}

@Composable
internal fun rememberScreenFailures(): ScreenFailures = rememberSaveable(saver = mapSaver(
    save = { it.entries.toMap() },
    restore = { values -> ScreenFailures(values.mapValues { it.value as String }) },
)) { ScreenFailures() }

@Composable
internal fun RecoverableScreen(screen: String, failures: ScreenFailures,
    header: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val entry = failures.entries[screen]
    var generation by remember(screen) { mutableStateOf(0) }
    if (entry == null) {
        key(screen, generation) {
            GuardedContent("screen-$screen", { failures.record(screen, it) }, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(GridlineSpacing.sp2),
                    verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
                    header()
                    ScreenRenderProbe.before(screen)
                    content()
                }
            }
        }
        return
    }
    val context = LocalContext.current
    var shareFailed by remember(screen) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(GridlineSpacing.sp2),
        verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        Notice(stringResource(R.string.screen_failed), kind = NoticeKind.Danger, tag = "screen-failure-$screen") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
                Button(stringResource(R.string.notice_share_log), {
                    try {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, entry), null))
                        shareFailed = false
                    } catch (failure: Exception) {
                        shareFailed = true
                        android.util.Log.w("ScreenRecovery", "Log sharing refused")
                    }
                }, tag = "screen-share-log")
                Button(stringResource(R.string.screen_retry), { generation++; failures.entries.remove(screen) }, tag = "screen-retry")
                if (shareFailed) ticktock.design.components.GlText(stringResource(R.string.screen_share_failed))
            }
        }
    }
}
