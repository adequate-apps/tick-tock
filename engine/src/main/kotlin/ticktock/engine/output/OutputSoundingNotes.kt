// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import ticktock.core.contracts.SoundingNotes

/**
 * The output side of the bleed policy (POL-24): the notes the stream is
 * putting into the room right now. The control thread writes it after every
 * voice change, open, close, park and mute; the analysis thread reads it on
 * every hop. A closed or muted stream leaks nothing, so it reports no notes
 * then, and the policy releases its duck on its own.
 */
class OutputSoundingNotes : SoundingNotes {
    @Volatile
    private var notes: IntArray = EMPTY

    override fun current(): IntArray = notes.let { if (it.isEmpty()) EMPTY else it.copyOf() }

    /** Called on the control thread only. */
    fun update(midi: IntArray) {
        notes = if (midi.isEmpty()) EMPTY else midi.copyOf()
    }

    fun clear() = update(EMPTY)

    private companion object {
        val EMPTY = IntArray(0)
    }
}
