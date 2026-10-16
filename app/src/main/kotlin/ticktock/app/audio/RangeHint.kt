// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.audio

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ticktock.app.config.effectiveRange
import ticktock.core.catalogue.Catalogue
import ticktock.core.catalogue.Instrument
import ticktock.core.prefs.Prefs
import ticktock.core.ranges.EffectiveRange
import ticktock.data.prefs.PrefsPublication

enum class RangeHintDirection { BELOW, ABOVE, OUTSIDE }

/** A range judgment belongs to the exact instrument selection that supplied its evidence. */
data class RangeHint(
    val instrumentKey: String,
    val instrumentRevision: Long,
    val range: EffectiveRange,
    val direction: RangeHintDirection,
) {
    /** Hide old evidence even when the source publication's listener has not reached analysis yet. */
    fun matches(publication: PrefsPublication): Boolean {
        if (instrumentRevision != publication.instrumentRevision || instrumentKey != publication.prefs.instrumentKey) return false
        val instrument = Catalogue.find(instrumentKey) ?: return false
        return !instrument.catchAll && range == effectiveRange(instrument, publication.prefs)
    }
}

/** Immutable settings snapshot; the counter using it belongs solely to the analysis thread. */
internal data class RangeHintProfile(val instrument: Instrument, val range: EffectiveRange) {
    companion object {
        fun from(prefs: Prefs): RangeHintProfile {
            val instrument = Catalogue.get(prefs.instrumentKey)
            return RangeHintProfile(instrument, effectiveRange(instrument, prefs))
        }
    }
}

/** Versioned invalidation prevents a delayed analysis callback from restoring cleared evidence. */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
internal class RangeHintFlow : StateFlow<RangeHint?> {
    private data class Update(val generation: Long, val hint: RangeHint?)
    private val updates = MutableStateFlow(Update(0, null))

    override val value: RangeHint? get() = updates.value.hint
    override val replayCache: List<RangeHint?> get() = listOf(value)

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun collect(collector: FlowCollector<RangeHint?>): Nothing {
        updates.map { it.hint }.distinctUntilChanged().collect(collector)
        error("Range hint state cannot complete")
    }

    fun invalidate(generation: Long) {
        while (true) {
            val previous = updates.value
            if (previous.generation >= generation) return
            if (updates.compareAndSet(previous, Update(generation, null))) return
        }
    }

    fun publish(generation: Long, hint: RangeHint?) {
        while (true) {
            val previous = updates.value
            if (previous.generation > generation) return
            if (updates.compareAndSet(previous, Update(generation, hint))) return
        }
    }
}
