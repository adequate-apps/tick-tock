// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import ticktock.core.contracts.InputSource
import ticktock.core.contracts.SoundingNotes
import ticktock.core.music.Pitch

/** An output that sounds nothing; the default until the registry is wired. */
object NoSoundingNotes : SoundingNotes {
    private val none = IntArray(0)
    override fun current(): IntArray = none
}

/**
 * The one detector (spec 2). Each hop is a reject, a skip or a vote, and the
 * readout, the histogram and the saved history all read the [Decision] it
 * returns. It is pure in the sense that matters: no clock, no thread, no
 * I/O; time is the hop index the caller passes (POL-09).
 *
 * How a note is confirmed and settled, in hops, for a mode with confirm C
 * and edge E:
 *  - the last [ModeParams.voteWindow] classified hops are the only memory
 *    (POL-03); skipped hops are not classified and age nothing (POL-25);
 *  - a note with C votes in the window wins and becomes the incumbent; the
 *    readout shows it from that hop (POL-02) and an [Attack] is emitted with
 *    the mean cents of the confirming hops (POL-06, ATK-02);
 *  - the winning hop and the next E readings are the attack edge: shown,
 *    never saved;
 *  - later readings queue in a pending list E deep; each new one releases
 *    the oldest to history, so the first saved reading lands at hop C + 2E
 *    (POL-01) and the last E readings of a note are still pending when it
 *    ends and are discarded with it (POL-07);
 *  - a note ends when silence outlasts the release edge, when no vote for it
 *    is left in the window, or when another note wins (POL-02, POL-14). A
 *    re-attack after silence confirms afresh on its own hops, so its edge is
 *    dropped again (POL-08).
 *
 * Silence shorter than the release edge is a glitch the held note rides out:
 * the pending queue survives it and the readout holds. Silence longer than
 * the edge is the end of the note whatever votes the window still holds,
 * because the tail that drooped into it and the scoop that follows it are
 * what the edges exist to keep out of history (POL-07). The edge is the
 * mode's allowance for a transient at either end of a note, so the same
 * figure sizes the gap that ends one.
 */
class Detector(
    mode: ResponseMode = ResponseMode.DEFAULT,
    source: InputSource = InputSource.UNPROCESSED,
    noiseFloorDb: Int? = null,
    a4: A4Generation = A4Generation(),
    private val sounding: SoundingNotes = NoSoundingNotes,
    /** False for an output source that cannot act on a duck (POL-29). */
    private val duckable: Boolean = true,
) {
    var mode: ResponseMode = mode
        private set
    var a4: A4Generation = a4
        private set
    var params: ModeParams = ResponseModeTable.of(mode)
        private set

    /** The capture preset the gate is scaled for (SRC-03); the analyser sets it from the input that opened. */
    var source: InputSource = source
        private set

    /** The player's "ignore sounds quieter than" setting, in dBFS on the UNPROCESSED scale, or null for the mode's floor alone. */
    var noiseFloorDb: Int? = noiseFloorDb
        private set

    private var floor = Gate.floor(params, source, noiseFloorDb)

    private var window = VoteWindow(params.voteWindow)
    private var incumbent: Int? = null
    private var edgeRemaining = 0
    private val pending = ArrayDeque<Reading>()
    private var awaitingSettle = false
    private var bleed = BleedState.IDLE

    /** Consecutive hops rejected as silence; anything else sounding resets it. */
    private var silentHops = 0

    /** The hop most recently classified, -1 before the first; a release raised between hops is stamped with it. */
    private var lastHop = -1L

    /** The note the readout shows, or null for a blank readout. */
    val shownNote: Int? get() = incumbent

    /** Readings held back for the release edge; a test can watch them drain. */
    val pendingCount: Int get() = pending.size

    val bleedState: BleedState get() = bleed

    /**
     * A mode change forgets the held note and its pending readings (POL-05).
     * Returns the release of the note it forgot, so an attack never goes
     * unpaired, or null when nothing was held.
     */
    fun setMode(newMode: ResponseMode): Release? {
        mode = newMode
        params = ResponseModeTable.of(newMode)
        floor = Gate.floor(params, source, noiseFloorDb)
        window = VoteWindow(params.voteWindow)
        return forget(ReleaseCause.MODE_CHANGE)
    }

    /**
     * The preset the input actually opened with, known only after the capture
     * chain has fallen through UNPROCESSED, VOICE_RECOGNITION and MIC. The
     * floor moves with it; the held note does not, because the preset says
     * nothing about pitch.
     */
    fun setSource(newSource: InputSource) {
        source = newSource
        floor = Gate.floor(params, source, noiseFloorDb)
    }

    /** The player's noise-floor setting changed; the gate follows it from the next hop. */
    fun setNoiseFloor(db: Int?) {
        noiseFloorDb = db
        floor = Gate.floor(params, source, noiseFloorDb)
    }

    /**
     * A new reference, a new generation, and nothing graded against the old one
     * survives (POL-20, POL-21). The same value still turns the generation.
     * Returns the release of the note it forgot, or null when nothing was held.
     */
    fun setA4(hz: Double): Release? {
        a4 = a4.changedTo(hz)
        return forget(ReleaseCause.A4_CHANGE)
    }

    /**
     * Forgets the held note, its pending readings and the vote window, as
     * when the stream restarts. The bleed state is about the output and
     * stays. Returns the release of the note it forgot, or null.
     */
    fun reset(): Release? = forget(ReleaseCause.RESTART)

    private fun forget(cause: ReleaseCause): Release? {
        val release = if (incumbent != null) endNote(lastHop, cause) else null
        window.clear()
        silentHops = 0
        return release
    }

    /**
     * Whether a window at this RMS is silence to the gate, under [micGain]:
     * [step] rejects such a window before it reads anything else about it,
     * so an estimator need not be run on it (DET-17).
     */
    fun belowFloor(rms: Double, micGain: Double = 1.0): Boolean = rms * micGain < floor

    /**
     * Classifies one hop. [micGain] scales the gate and the meter only; the
     * estimate's frequency is what it is, so cents are the same for every
     * gain that passes (POL-12).
     */
    fun step(estimate: HopEstimate, hop: Long, micGain: Double = 1.0): Decision {
        lastHop = hop
        val level = estimate.rms * micGain
        var reason: RejectReason? = when {
            belowFloor(estimate.rms, micGain) -> RejectReason.SILENCE
            !estimate.hasCandidate -> RejectReason.NO_CANDIDATE
            estimate.aperiodicity > params.aperiodicityCeiling -> RejectReason.APERIODIC
            else -> null
        }
        var midi: Int? = null
        var cents = 0.0
        if (reason == null) {
            val nearest = Pitch.nearest(estimate.hz, a4.hz)
            when {
                nearest == null -> reason = RejectReason.NO_CANDIDATE
                nearest.midi in Pitch.DETECTOR_WINDOW -> {
                    midi = nearest.midi
                    cents = nearest.cents
                }
                else -> reason = RejectReason.OUTSIDE_WINDOW
            }
        }

        silentHops = if (reason == RejectReason.SILENCE) silentHops + 1 else 0

        val bleedStep = BleedPolicy.step(bleed, sounding.current(), midi, level, duckable)
        bleed = bleedStep.state
        val duck = bleedStep.duck

        if (midi != null && bleedStep.exclude) {
            // A skipped hop leaves the incumbent and its pending readings alone
            // (POL-25), unless the incumbent itself is what the output sounds
            // and the player is not proven to hold it: then it must not stay
            // on the readout. An admitted note rides out a weak hop; it ends
            // when the admission lapses.
            val unproven = incumbent == midi && bleed.admitted != midi
            val release = if (unproven) endNote(hop, ReleaseCause.BLEED) else null
            return Decision(hop, HopClass.SKIP, skip = SkipReason.BLEED, level = level, incumbent = incumbent, release = release, duck = duck)
        }
        if (reason != null) {
            window.push(VoteWindow.NO_NOTE, 0.0)
            val held = incumbent
            var release: Release? = null
            if (held != null && silentHops > params.edge) {
                // The note is over: its tail goes with it, and the window is
                // cleared so the next sound, even the same note, has to win on
                // its own hops (POL-08).
                release = endNote(hop, ReleaseCause.SILENCE)
                window.clear()
            } else if (held != null && window.votes(held) == 0) {
                release = endNote(hop, ReleaseCause.SILENCE)
            }
            return Decision(hop, HopClass.REJECT, reject = reason, level = level, incumbent = incumbent, release = release, duck = duck)
        }
        return vote(estimate.hz, cents, hop, midi!!, level, duck)
    }

    private fun vote(hz: Double, cents: Double, hop: Long, midi: Int, level: Double, duck: Float?): Decision {
        val reading = Reading(
            hop = hop,
            midiSounding = midi,
            hz = hz,
            cents = cents,
            a4Hz = a4.hz,
            a4Generation = a4.generation,
        )
        window.push(midi, reading.cents)
        val held = incumbent

        if (midi == held) {
            if (edgeRemaining > 0) {
                edgeRemaining--
                return Decision(hop, HopClass.VOTE, level = level, incumbent = midi, display = reading, duck = duck)
            }
            val saved = enqueue(reading)
            val settled = saved != null && awaitingSettle
            if (settled) awaitingSettle = false
            return Decision(hop, HopClass.VOTE, level = level, incumbent = midi, display = reading, saved = saved, firstSettled = settled, duck = duck)
        }

        if (window.votes(midi) >= params.confirm) {
            val landing = window.meanCents(midi)
            val release = if (held != null) endNote(hop, ReleaseCause.NOTE_CHANGE) else null
            incumbent = midi
            edgeRemaining = params.edge
            awaitingSettle = true
            val attack = Attack(hop, midi, landing, a4.generation)
            return Decision(hop, HopClass.VOTE, level = level, incumbent = midi, display = reading, attack = attack, release = release, duck = duck)
        }

        // A challenger without a majority: the readout holds the incumbent,
        // which is never blank between two notes (POL-02).
        val release = if (held != null && window.votes(held) == 0) endNote(hop, ReleaseCause.UNCONFIRMED_NOTES) else null
        return Decision(hop, HopClass.VOTE, level = level, incumbent = incumbent, release = release, duck = duck)
    }

    /** Queues [reading] behind the release edge; returns the reading it pushed out to history, if any. */
    private fun enqueue(reading: Reading): Reading? {
        // With edge 0 nothing is held back: the reading itself is history.
        if (params.edge == 0) return reading
        val released = if (pending.size >= params.edge) pending.removeFirst() else null
        pending.addLast(reading)
        return released
    }

    private fun endNote(hop: Long, cause: ReleaseCause): Release {
        val note = incumbent ?: throw IllegalStateException("no note to end")
        val discarded = pending.size
        pending.clear()
        incumbent = null
        edgeRemaining = 0
        awaitingSettle = false
        silentHops = 0
        return Release(hop, note, discarded, cause)
    }
}

/**
 * The last N classified hops. A vote is a note; a reject is [NO_NOTE]. The
 * ring is the whole memory of the vote (POL-03).
 */
private class VoteWindow(val size: Int) {
    private val notes = IntArray(size) { NO_NOTE }
    private val cents = DoubleArray(size)
    private var next = 0

    fun push(note: Int, centsOfNote: Double) {
        notes[next] = note
        cents[next] = centsOfNote
        next = (next + 1) % size
    }

    fun votes(note: Int): Int = notes.count { it == note }

    fun meanCents(note: Int): Double {
        var sum = 0.0
        var n = 0
        for (i in 0 until size) {
            if (notes[i] == note) {
                sum += cents[i]
                n++
            }
        }
        return if (n == 0) Double.NaN else sum / n
    }

    fun clear() {
        notes.fill(NO_NOTE)
        cents.fill(0.0)
        next = 0
    }

    companion object {
        const val NO_NOTE = -1
    }
}
