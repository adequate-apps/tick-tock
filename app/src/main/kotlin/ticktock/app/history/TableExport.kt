// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.history

import java.time.Instant
import ticktock.core.catalogue.Catalogue
import ticktock.core.contracts.AlwaysEntitled
import ticktock.core.contracts.Entitlement
import ticktock.core.contracts.GatedFeature
import ticktock.core.export.StatisticsCsv
import ticktock.core.export.StatisticsRow
import ticktock.core.music.NoteSpelling
import ticktock.core.music.Transposition
import ticktock.core.prefs.Prefs
import ticktock.data.history.HistoryStore
import ticktock.data.history.Scope

class TableExport(private val access: Entitlement = AlwaysEntitled) {
    val allowed: Boolean get() = access.has(GatedFeature.EXPORT)

    suspend fun encode(store: HistoryStore, scope: Scope, prefs: Prefs): ByteArray? {
        if (!allowed) return null
        val rows = store.export(scope).map { stored ->
            val note = stored.note
            val label = note.label
            val instrument = Catalogue.find(label.instrumentKey) ?: Catalogue.get(Catalogue.DEFAULT_KEY)
            val names = NoteSpelling.resolve(instrument, prefs.spelling.accidentals)
            val horn = if (label.nickname.isBlank()) "" else " · ${label.nickname}"
            StatisticsRow((Catalogue.find(label.instrumentKey)?.displayName ?: label.instrumentKey) + horn,
                label.a4Hz, NoteSpelling.name(Transposition.writtenFromSounding(note.midiSounding, label.transposition), names),
                NoteSpelling.name(note.midiSounding, names), note.n, note.attacks, note.meanCents, note.spreadCents,
                stored.kind.sql, stored.equipment, stored.session, Instant.ofEpochMilli(stored.startedAt).toString())
        }
        return StatisticsCsv.encode(rows)
    }
}
