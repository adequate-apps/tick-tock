// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ticktock.core.prefs.Prefs
import ticktock.data.history.HistorySnapshot
import ticktock.data.history.HistoryStore
import ticktock.data.history.RunLabel
import ticktock.data.history.Scope
import ticktock.data.history.SessionPurpose

/** The table refreshes at storage boundaries while visible. */
data class TableState(
    val scope: Scope = Scope.CurrentSession,
    val snapshot: HistorySnapshot? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val gated: Boolean = false,
    val error: String? = null,
)

class TableSession(
    private val store: HistoryStore,
    private val readPrefs: () -> Prefs,
    private val writePrefs: ((Prefs) -> Prefs) -> Unit,
    val allHistory: AllHistoryAccess = AllHistoryAccess(),
    val namedSessions: NamedSessionsAccess = NamedSessionsAccess(),
    val export: TableExport = TableExport(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow(TableState())
    val state: StateFlow<TableState> = mutableState
    private var observation: Job? = null
    private var loading: Job? = null

    fun activate() {
        if (observation != null) return
        observation = scope.launch {
            store.status.map { it.flushes }.distinctUntilChanged().collect { refresh() }
        }
    }

    fun deactivate() {
        observation?.cancel()
        observation = null
        loading?.cancel()
        loading = null
    }

    fun select(selected: Scope): Job {
        val denied = when (selected) {
            is Scope.AllHistory -> !allHistory.allowed
            is Scope.Session -> !namedSessions.allowed
            Scope.CurrentSession -> false
        }
        mutableState.update { it.copy(scope = selected, snapshot = null, gated = denied, error = null) }
        return refresh()
    }

    fun includeDroneRuns(include: Boolean): Job {
        writePrefs { it.copy(includeDroneRuns = include) }
        return select(Scope.AllHistory(include))
    }

    fun refresh(): Job {
        loading?.cancel()
        val selected = state.value.scope
        val gated = state.value.gated
        return scope.launch {
            if (gated) { mutableState.update { it.copy(loading = false) }; return@launch }
            mutableState.update { it.copy(loading = true) }
            try {
                val snapshot = store.snapshot(selected, readPrefs().includeDroneRuns)
                mutableState.update { it.copy(snapshot = snapshot, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(loading = false, error = "Phone storage refused table read") } }
        }.also { loading = it }
    }

    fun save(id: Long, name: String, equipment: String, purpose: SessionPurpose, notes: String): Job = action("save") {
        if (!namedSessions.allowed) return@action
        store.saveSession(id, name, equipment, purpose, notes)
        writePrefs { it.copy(lastEquipment = equipment.trim()) }
    }

    fun resume(id: Long): Job = action("resume") {
        if (!namedSessions.allowed) return@action
        store.resumeSession(id)
        mutableState.update { it.copy(scope = Scope.CurrentSession, gated = false) }
    }

    fun newSession(): Job = action("new session") {
        store.newSession()
        mutableState.update { it.copy(scope = Scope.CurrentSession, gated = false) }
    }

    fun delete(id: Long): Job = action("delete") {
        if (!namedSessions.allowed && id != state.value.snapshot?.currentSessionId) return@action
        store.deleteSession(id)
        if (state.value.scope == Scope.Session(id)) mutableState.update { it.copy(scope = Scope.CurrentSession, gated = false) }
    }

    fun clearNote(selected: Scope, label: RunLabel, midi: Int): Job = action("clear note") {
        when (selected) {
            is Scope.AllHistory -> if (!allHistory.allowed) return@action
            is Scope.Session -> if (!namedSessions.allowed && selected.id != state.value.snapshot?.currentSessionId) return@action
            Scope.CurrentSession -> Unit
        }
        store.clearNote(selected, label, midi)
    }

    fun clearHistory(): Job = action("clear history") { store.clearHistory() }

    suspend fun csv(): ByteArray? {
        if (state.value.gated) return null
        return try { export.encode(store, state.value.scope, readPrefs()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.update { it.copy(error = "Phone storage refused CSV export") }; null }
    }

    private fun action(name: String, block: suspend () -> Unit): Job {
        if (state.value.busy) return Job().apply { complete() }
        mutableState.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                block()
                refresh().join()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = "Phone storage refused $name") } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    fun dismissError() { mutableState.update { it.copy(error = null) } }

    fun close() { scope.cancel() }
}
