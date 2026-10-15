// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ticktock.app.tune.TuneSession
import ticktock.app.output.DeviceOutputConnection
import ticktock.app.output.OutputConnection
import ticktock.app.output.OutputSession
import ticktock.core.catalogue.Catalogue
import ticktock.core.contracts.HistorySink
import ticktock.core.detect.Reading
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.Theme
import ticktock.data.Storage
import ticktock.data.StorageThread
import ticktock.data.crash.CrashLog
import ticktock.data.db.HistoryDatabase
import ticktock.data.history.HistoryStore
import ticktock.data.history.RunContext
import ticktock.data.history.RunKind
import ticktock.data.prefs.AtomicPrefsFile
import ticktock.data.prefs.PrefsLoad
import ticktock.data.prefs.PrefsStore

/**
 * The app-wide objects, built once per process. Prefs load before the history
 * store exists, so the first run carries the saved instrument, nickname and
 * capture state (SES-01); every later prefs change relabels the next run
 * (SES-02).
 */
class AppGraph(
    context: Context,
    filesDir: File = context.filesDir,
    databaseName: String? = Storage.DATABASE_FILE,
    makeOutput: () -> OutputConnection = { DeviceOutputConnection(context) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val storage = StorageThread()
    val crashLog = CrashLog(filesDir)
    val prefs = PrefsStore(AtomicPrefsFile(File(filesDir, Storage.PREFS_FILE)), storage)
    val prefsLoad: PrefsLoad = prefs.load()
    private val database = HistoryDatabase(context, databaseName)
    val history = HistoryStore(database, storage, runContextOf(prefs.prefs.value))
    private val historyContextLock = Any()

    val output = OutputSession(
        readPrefs = { prefs.prefs.value }, writePrefs = prefs::update,
        makeConnection = makeOutput, changed = ::refreshHistoryContext,
    )

    /** Read actual sounding notes at the reading boundary, including a route event still queued for the UI. */
    internal val captureHistory = object : HistorySink {
        override val rejected: Int get() = history.rejected
        override fun accept(reading: Reading, firstSettled: Boolean) {
            val context = currentRunContext()
            synchronized(historyContextLock) {
                history.setContext(context)
                history.accept(reading, firstSettled)
            }
        }
    }

    /** The microphone and the live readout. */
    val tune = TuneSession(context, prefs.prefs.value, captureHistory, sounding = output, onDuck = output::duck)

    /** The screen theme, as its own flow so the root recomposes on a theme change and on nothing else in prefs. */
    val theme: StateFlow<Theme> = prefs.prefs.map { it.theme }.stateIn(scope, SharingStarted.Eagerly, prefs.prefs.value.theme)

    init {
        output.bleedContextChanged = tune::outputContextChanged
        crashLog.install { prefs.prefs.value.hornNickname }
        history.start()
        // On the changing thread, so no reading after a label change carries the old label.
        prefs.addListener {
            output.settingsChanged()
            refreshHistoryContext()
            tune.settingsChanged(it)
        }
    }

    /** The app came to the foreground: listen again if the microphone is allowed. */
    fun onForeground() {
        output.foreground()
        tune.resume()
    }

    /** The app went to the background: stop listening and write what is pending now (OUT-11, SES-06). */
    fun onBackground() {
        tune.pause()
        output.background()
        prefs.flushSoon()
        history.flushSoon()
    }

    /** Tests only: a process never closes its graph. */
    fun close() {
        tune.close()
        output.close()
        scope.cancel()
        history.stop()
        storage.shutdown()
        database.close()
    }

    private fun currentRunContext(): RunContext {
        val drone = output.soundingDroneMidi
        return runContextOf(prefs.prefs.value, drone)
    }

    private fun refreshHistoryContext() {
        val context = currentRunContext()
        synchronized(historyContextLock) { history.setContext(context) }
    }

    companion object {
        fun runContextOf(p: Prefs, droneMidi: Int? = null): RunContext = RunContext(
            instrumentKey = p.instrumentKey,
            transposition = Catalogue.find(p.instrumentKey)?.transposition ?: 0,
            nickname = p.hornNickname,
            captured = !p.capturePaused,
            kind = if (droneMidi == null) RunKind.FREE else RunKind.DRONE,
            droneMidi = droneMidi,
        )
    }
}
