// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts.fakes

import ticktock.core.contracts.SoundingNotes

/** A [SoundingNotes] whose set a test assigns directly. */
class FakeSoundingNotes(vararg initial: Int) : SoundingNotes {
    var notes: IntArray = initial

    override fun current(): IntArray = notes.copyOf()
}
