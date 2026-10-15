// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

/**
 * What the output is sounding right now, as sounding MIDI numbers. The bleed
 * policy reads this on every hop to keep the app's own drone and pipes out of
 * the vote (POL-24). Implementations return a fresh array or an empty one;
 * callers never mutate it.
 */
interface SoundingNotes {
    fun current(): IntArray
}
