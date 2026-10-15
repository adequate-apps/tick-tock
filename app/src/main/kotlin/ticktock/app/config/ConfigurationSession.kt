// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.config

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ticktock.app.R
import ticktock.core.config.ParseResult
import ticktock.core.config.PersonalConfig
import ticktock.core.config.PersonalConfigCodec
import ticktock.core.prefs.PersonalConfiguration
import ticktock.data.prefs.PrefsStore

internal data class ConfigurationState(
    val incoming: PersonalConfig? = null,
    val busy: Boolean = false,
    val message: Int? = null,
    val failed: Boolean = false,
)

/** Document work and its bounded preview survive recreation without entering a saved-state bundle. */
internal class ConfigurationSession(
    context: Context,
    private val prefs: PrefsStore,
    private val read: (Context, Uri) -> ParseResult = ConfigurationFiles::read,
) {
    private val context = context.applicationContext
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ConfigurationState())
    val state: StateFlow<ConfigurationState> = mutableState

    fun open(uri: Uri) = action(R.string.config_read_error) {
        when (val parsed = withContext(Dispatchers.IO) { read(context, uri) }) {
            is ParseResult.Ok -> ConfigurationState(incoming = parsed.config)
            is ParseResult.Failed -> ConfigurationState(message = R.string.config_invalid, failed = true)
        }
    }

    fun export(uri: Uri) = action(R.string.config_write_error) {
        val bytes = PersonalConfigCodec.write(PersonalConfiguration.snapshot(prefs.prefs.value)).toByteArray(Charsets.UTF_8)
        withContext(Dispatchers.IO) { ConfigurationFiles.write(context, uri, bytes) }
        ConfigurationState(message = R.string.config_exported)
    }

    fun interruptedImport() { mutableState.value = ConfigurationState(message = R.string.config_interrupted, failed = true) }

    fun cancel() { if (!state.value.busy) mutableState.value = ConfigurationState() }

    fun apply() = action(R.string.config_apply_error) {
        val config = state.value.incoming ?: return@action ConfigurationState()
        prefs.update { PersonalConfiguration.apply(it, config) }
        withContext(Dispatchers.IO) { prefs.flushAndWait().getOrThrow() }
        ConfigurationState(message = R.string.config_applied)
    }

    private fun action(error: Int, work: suspend () -> ConfigurationState): Job? {
        if (state.value.busy) return null
        mutableState.value = state.value.copy(busy = true, message = null, failed = false)
        return jobs.launch {
            try { mutableState.value = work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = state.value.copy(busy = false, message = error, failed = true) }
        }
    }

    fun close() { jobs.cancel() }
}
