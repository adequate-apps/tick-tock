// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ticktock.core.catalogue.Catalogue
import ticktock.core.prefs.Prefs
import ticktock.data.Storage
import ticktock.data.StorageThread
import ticktock.data.crash.CrashLog
import ticktock.data.db.HistoryDatabase
import ticktock.data.history.HistoryStore
import ticktock.data.history.RunContext
import ticktock.data.prefs.AtomicPrefsFile
import ticktock.data.prefs.PrefsLoad
import ticktock.data.prefs.PrefsStore
import java.io.File

/**
 * The app-wide objects, built once per process. Prefs load before the history
 * store exists, so the first run carries the saved instrument, nickname and
 * capture state (SES-01); every later prefs change relabels the next run
 * (SES-02).
 */
class AppGraph(context: Context, filesDir: File = context.filesDir, databaseName: String? = Storage.DATABASE_FILE) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val storage = StorageThread()
    val crashLog = CrashLog(filesDir)
    val prefs = PrefsStore(AtomicPrefsFile(File(filesDir, Storage.PREFS_FILE)), storage)
    val prefsLoad: PrefsLoad = prefs.load()
    private val database = HistoryDatabase(context, databaseName)
    val history = HistoryStore(database, storage, runContextOf(prefs.prefs.value))

    init {
        crashLog.install { prefs.prefs.value.hornNickname }
        history.start()
        scope.launch {
            prefs.prefs.map(::runContextOf).distinctUntilChanged().collect(history::setContext)
        }
    }

    /** The app went to the background: write what is pending now (SES-06, PLAN 3.4). */
    fun onBackground() {
        prefs.flushSoon()
        history.flushSoon()
    }

    /** Tests only: a process never closes its graph. */
    fun close() {
        scope.cancel()
        history.stop()
        storage.shutdown()
        database.close()
    }

    companion object {
        fun runContextOf(p: Prefs): RunContext = RunContext(
            instrumentKey = p.instrumentKey,
            transposition = Catalogue.find(p.instrumentKey)?.transposition ?: 0,
            nickname = p.hornNickname,
            captured = !p.capturePaused,
        )
    }
}
