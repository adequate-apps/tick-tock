// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.AppGraph
import ticktock.app.R
import ticktock.data.prefs.PrefsNotice
import ticktock.design.GridlineSpacing
import ticktock.design.components.Button
import ticktock.design.components.Notice
import ticktock.design.components.NoticeKind

/**
 * What the player must know before anything else, each in one line with the
 * key that deals with it: a crash last time (ERR-02, offered once), prefs that
 * were reset or cannot be read (ERR-04, ERR-05), history that has stopped
 * saving (SES-07). Nothing shows when all is well.
 */
@Composable
fun AppNotices(graph: AppGraph) {
    val context = LocalContext.current
    val prefsNotice by graph.prefs.notice.collectAsStateWithLifecycle()
    val store by graph.history.status.collectAsStateWithLifecycle()
    var crash by remember { mutableStateOf(graph.crashLog.pendingOffer()) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        crash?.let { log ->
            Notice(text = stringResource(R.string.notice_crash), kind = NoticeKind.Danger, tag = "notice-crash") {
                Keys(
                    first = stringResource(R.string.notice_share_log) to {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log)
                        context.startActivity(Intent.createChooser(send, null))
                        graph.crashLog.markOffered()
                        crash = null
                    },
                    second = stringResource(R.string.notice_dismiss) to {
                        graph.crashLog.markOffered()
                        crash = null
                    },
                )
            }
        }
        prefsNotice?.let { notice ->
            val line = when (notice) {
                PrefsNotice.CORRUPT_COPY_KEPT -> R.string.notice_prefs_reset
                PrefsNotice.UNREADABLE -> R.string.notice_prefs_unreadable
                PrefsNotice.SAVE_FAILING -> R.string.notice_prefs_saving
            }
            Notice(text = stringResource(line), kind = if (notice == PrefsNotice.CORRUPT_COPY_KEPT) NoticeKind.Plain else NoticeKind.Danger, tag = "notice-prefs") {
                Button(stringResource(R.string.notice_dismiss), onClick = graph.prefs::acknowledgeNotice, tag = "notice-prefs-dismiss")
            }
        }
        if (store.failing) {
            Notice(text = stringResource(R.string.notice_history), kind = NoticeKind.Danger, tag = "notice-history")
        }
    }
}

@Composable
private fun Keys(first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>) {
    Row(horizontalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
        Button(first.first, onClick = first.second, tag = "notice-key-1")
        Button(second.first, onClick = second.second, tag = "notice-key-2")
    }
}
