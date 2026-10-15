// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import kotlin.math.ceil

/**
 * The bleed policy's memory between hops. Immutable: [BleedPolicy.step] takes
 * one and returns the next, so every transition is a value a test can write
 * down.
 */
data class BleedState(
    /** Consecutive hops that detected a note the output is sounding. */
    val suspectHops: Int = 0,
    /** Consecutive hops that detected nothing the output is sounding. */
    val clearHops: Int = 0,
    val ducked: Boolean = false,
    /** Eligible capture hops since the duck acknowledgement; proof waits [BleedPolicy.RAMP_HOPS]. */
    val hopsSinceDuck: Int = 0,
    /** Level on the hop the duck was commanded; the unison test compares against it. */
    val preDuckLevel: Double = Double.NaN,
    /** Consecutive hops at the floor whose level stayed near the pre-duck level, all for [strongNote]. */
    val strongHops: Int = 0,
    /** The sounding note the strong hops were counted for; evidence for one note says nothing about another. */
    val strongNote: Int? = null,
    /** A sounding note proven to be the player's too; its readings are saved while the level keeps proving it. */
    val admitted: Int? = null,
    /** Consecutive hops of the admitted note whose level fell under the unison ratio. */
    val weakHops: Int = 0,
) {
    companion object {
        val IDLE = BleedState()
    }
}

/** One hop's outcome: the next state, whether the candidate is excluded, and a gain to send if it changed. */
data class BleedStep(
    val state: BleedState,
    val exclude: Boolean,
    val duck: Float? = null,
)

/**
 * Keeps the app's own drone and pipes out of the vote (spec 2.6). A candidate
 * that matches a sounding note is a suspected leak and is skipped. Three
 * suspect hops duck the output to the floor; once the ramp has had time to
 * land, a level that stays near the pre-duck level for five hops can only be
 * the player holding the same note, so the note is admitted and measured
 * (POL-27). Leakage alone falls with the duck and is never admitted (POL-28);
 * an output that cannot duck never gets the chance (POL-29). The duck releases
 * after three clear hops.
 *
 * An admission holds only while the level keeps proving it: the drone keeps
 * leaking the same note after the player stops or breathes, so a hop of the
 * admitted note under the ratio is skipped, and [LAPSE_HOPS] such hops in a
 * row withdraw the admission, which then has to be earned again (POL-28).
 */
object BleedPolicy {
    const val SUSPECT_HOPS = 3
    const val CLEAR_HOPS = 3
    const val UNISON_HOPS = 5

    /** Weak hops that end an admission: one is a dip or a glitch, three is the player gone, as for a leak suspected or cleared. */
    const val LAPSE_HOPS = 3
    private const val DUCK_FLOOR_LINEAR = 0.30
    val DUCK_FLOOR: Float = DUCK_FLOOR_LINEAR.toFloat()
    const val FULL_GAIN = 1.0f

    /** Additional acoustic settling allowance after the presented ramp and capture fence. */
    const val RAMP_MILLIS = 80.0

    /** Eligible hops before the unison test may begin. */
    val RAMP_HOPS: Int = ceil(RAMP_MILLIS / Hop.MILLIS).toInt()

    /**
     * Half way between the floor and full gain: with the drone at 0.30 the sum
     * of player and drone stays above this only when the player carries the
     * note.
     */
    const val UNISON_RATIO = (1.0 + DUCK_FLOOR_LINEAR) / 2.0

    /**
     * @param sounding what the output is sounding, from [ticktock.core.contracts.SoundingNotes]
     * @param candidate the hop's nearest note after the gates, or null for a rejected hop
     * @param level the hop's level after mic gain
     * @param duckable whether the output source can act on a duck command
     */
    fun step(state: BleedState, sounding: IntArray, candidate: Int?, level: Double, duckable: Boolean,
        evidenceReady: Boolean = true,
    ): BleedStep {
        if (sounding.isEmpty()) {
            // Nothing to leak. Restore the gain if a duck was in force so the next
            // start of the drone is at full level.
            val duck = if (state.ducked) FULL_GAIN else null
            return BleedStep(BleedState.IDLE, exclude = false, duck = duck)
        }
        val leak = candidate != null && candidate in sounding
        if (!leak) return clear(state)
        if (!evidenceReady) {
            // Buffered audio cannot prove how the player responded to a later gain change.
            return BleedStep(state.copy(suspectHops = 0, clearHops = 0, hopsSinceDuck = 0, strongHops = 0,
                strongNote = null, admitted = null, weakHops = 0), exclude = true)
        }
        if (state.admitted == candidate) return holdAdmission(state, level)
        return suspect(state, candidate, level, duckable)
    }

    /**
     * The admitted note again: still the player's while the level stays at
     * unison. Under the ratio it is what the leak alone gives, so the hop is
     * skipped, and a run of them withdraws the admission.
     */
    private fun holdAdmission(state: BleedState, level: Double): BleedStep {
        if (level >= UNISON_RATIO * state.preDuckLevel) return BleedStep(state.copy(clearHops = 0, weakHops = 0), exclude = false)
        val weakHops = state.weakHops + 1
        if (weakHops >= LAPSE_HOPS) {
            val lapsed = state.copy(clearHops = 0, weakHops = 0, strongHops = 0, strongNote = null, admitted = null)
            return BleedStep(lapsed, exclude = true)
        }
        return BleedStep(state.copy(clearHops = 0, weakHops = weakHops), exclude = true)
    }

    private fun clear(state: BleedState): BleedStep {
        val clearHops = state.clearHops + 1
        if (state.ducked && clearHops >= CLEAR_HOPS) {
            // The leak has been gone long enough; release the duck. Any unison
            // proof lapses with it and has to be earned again.
            return BleedStep(BleedState.IDLE, exclude = false, duck = FULL_GAIN)
        }
        return BleedStep(state.copy(suspectHops = 0, clearHops = clearHops, strongHops = 0, strongNote = null), exclude = false)
    }

    private fun suspect(state: BleedState, candidate: Int, level: Double, duckable: Boolean): BleedStep {
        val suspectHops = state.suspectHops + 1
        if (!state.ducked) {
            if (duckable && suspectHops >= SUSPECT_HOPS) {
                val next = state.copy(
                    suspectHops = suspectHops, clearHops = 0, ducked = true,
                    hopsSinceDuck = 0, preDuckLevel = level, strongHops = 0, admitted = null,
                )
                return BleedStep(next, exclude = true, duck = DUCK_FLOOR)
            }
            return BleedStep(state.copy(suspectHops = suspectHops, clearHops = 0), exclude = true)
        }
        val hopsSinceDuck = state.hopsSinceDuck + 1
        if (hopsSinceDuck < RAMP_HOPS) {
            // The ramp is still moving; a level read now says nothing about who is playing.
            return BleedStep(state.copy(suspectHops = suspectHops, clearHops = 0, hopsSinceDuck = hopsSinceDuck, weakHops = 0), exclude = true)
        }
        val strong = level >= UNISON_RATIO * state.preDuckLevel
        val continuing = candidate == state.strongNote
        val strongHops = when {
            strong && continuing -> state.strongHops + 1
            strong -> 1
            else -> 0
        }
        val admitted = if (strongHops >= UNISON_HOPS) candidate else null
        val next = state.copy(
            suspectHops = suspectHops, clearHops = 0, hopsSinceDuck = hopsSinceDuck,
            strongHops = strongHops, strongNote = candidate, admitted = admitted, weakHops = 0,
        )
        return BleedStep(next, exclude = admitted == null)
    }
}
