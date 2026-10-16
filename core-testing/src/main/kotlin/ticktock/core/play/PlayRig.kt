// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.play

import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.A4Generation
import ticktock.core.detect.Decision
import ticktock.core.detect.Detector
import ticktock.core.detect.Hop
import ticktock.core.detect.HopAnalyser
import ticktock.core.detect.ResponseMode
import ticktock.core.detect.YinEstimator
import ticktock.core.music.Cents
import ticktock.core.music.Pitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Plays a [Performance] into the real analysis path: a fake microphone hands
 * over blocks of 5 to 20 ms, as a capture thread does when the system is busy,
 * [HopAnalyser] cuts hops, YIN estimates and the [Detector] decides. Nothing
 * between the audio and the decisions is scripted.
 */
class PlayRig(private val performance: Performance, mode: ResponseMode = ResponseMode.NORMAL, seed: Int = 5) {
    val detector = Detector(mode, a4 = A4Generation(performance.a4Hz))
    private val estimator = YinEstimator(detector)
    private val analyser = HopAnalyser(detector, estimator)
    private val input = FakeAudioInput(sampleRate = performance.sampleRate)
    private val random = Random(seed)
    val decisions = ArrayList<Decision>()

    init {
        analyser.attach(input).getOrThrow()
    }

    fun play(): PlayRig {
        val samples = performance.samples
        val rate = performance.sampleRate
        var offset = 0
        while (offset < samples.size) {
            val frames = minOf(rate / 200 + random.nextInt(rate * 3 / 200), samples.size - offset)
            input.push(samples.copyOfRange(offset, offset + frames), frames)
            decisions += analyser.drain()
            offset += frames
        }
        return this
    }

    val analysisErrors: Long get() = analyser.counters.analysisErrors
}

/** How one reading compared with what was played over the same samples. */
data class Graded(
    val hop: Long,
    /** The hop of the decision that released the reading to history, after the detector held it pending. */
    val savedAt: Long,
    val noteIndex: Int,
    val readMidi: Int,
    val readCents: Double,
    val truthCents: Double,
    /** The window lay inside one note, past its attack: the reading must be exact. */
    val clean: Boolean,
) {
    val error: Double get() = readCents - truthCents
}

/**
 * The accuracy report for one take. Every saved reading is graded against
 * the truth over the samples its window covered (two hops ending at the
 * reading's hop). A clean window is one note's sustain, past its first 80 ms
 * and before its release; there the
 * reading must name the played note and match its pitch. A window that
 * straddles a change may name either note but no other.
 */
class Scorecard(val performance: Performance, private val decisions: List<Decision>, val title: String) {
    constructor(performance: Performance, rig: PlayRig, title: String) : this(performance, rig.decisions, title)

    private val hopFrames = Hop.frames(performance.sampleRate)
    private val attackFrames = (0.08 * performance.sampleRate).toInt()

    /** How long after a note stops its reverb may still be read as that note. */
    private val tailFrames = (0.6 * performance.sampleRate).toInt()

    /** Readings of the last note in the room's tail after the player stopped. */
    var tails = 0
        private set

    val graded: List<Graded>
    /** Readings naming a note that was not sounding anywhere in their window. */
    val phantoms = mutableListOf<String>()
    /** Clean windows read as the wrong note. */
    val wrongNotes = mutableListOf<String>()

    init {
        val out = ArrayList<Graded>()
        for ((index, decision) in decisions.withIndex()) {
            val reading = decision.saved ?: continue
            val first = ((reading.hop - 1) * hopFrames).toInt().coerceAtLeast(0)
            val last = ((reading.hop + 1) * hopFrames).toInt().coerceAtMost(performance.samples.size)
            val notesInWindow = (first until last).map { performance.noteAt[it] }.filter { it >= 0 }
            if (notesInWindow.isEmpty()) {
                // The room still rings with the last note after the player stops; reading
                // that note there is right, and anything else is invented.
                val lastNote = (first downTo maxOf(0, first - tailFrames)).firstNotNullOfOrNull { i -> performance.noteAt[i].takeIf { it >= 0 } }
                if (lastNote != null && performance.notes[lastNote].midi == reading.midiSounding) tails++ else {
                    phantoms += "hop ${reading.hop}: ${Pitch.label(reading.midiSounding)} read in a rest"
                }
                continue
            }
            val playedMidis = notesInWindow.toSet().map { performance.notes[it].midi }.toSet()
            if (reading.midiSounding !in playedMidis) {
                phantoms += "hop ${reading.hop}: ${Pitch.label(reading.midiSounding)} while ${playedMidis.map(Pitch::label)} sounded"
                continue
            }
            val note = notesInWindow.groupingBy { it }.eachCount().maxBy { it.value }.key
            val clean = notesInWindow.size == last - first && notesInWindow.all { it == note } &&
                first - performance.onsets[note] >= attackFrames && last <= performance.ends[note]
            // The truth is the mean log-frequency over the window, in cents from the note the reading names.
            val ref = Pitch.hzFromMidi(reading.midiSounding, performance.a4Hz)
            var sum = 0.0
            var n = 0
            for (i in first until last) {
                if (performance.noteAt[i] == note && performance.truthHz[i] > 0) {
                    sum += 1200.0 * ln(performance.truthHz[i] / ref) / ln(2.0)
                    n++
                }
            }
            if (clean && reading.midiSounding != performance.notes[note].midi) {
                wrongNotes += "hop ${reading.hop}: read ${Pitch.label(reading.midiSounding)}, played ${Pitch.label(performance.notes[note].midi)}"
            }
            out += Graded(reading.hop, decision.hop, note, reading.midiSounding, reading.cents, sum / n, clean)
        }
        graded = out
    }

    private val cleanErrors: List<Double> get() = graded.filter { it.clean }.map { abs(it.error) }.sorted()

    val maxError: Double get() = cleanErrors.lastOrNull() ?: 0.0
    val meanError: Double get() = if (cleanErrors.isEmpty()) 0.0 else cleanErrors.average()
    val p95Error: Double get() = if (cleanErrors.isEmpty()) 0.0 else cleanErrors[((cleanErrors.size - 1) * 0.95).roundToInt()]

    /** Clean readings whose printed whole cent is more than 1 c from the played pitch. */
    val shownOff: Int get() = graded.count { it.clean && abs((Cents.shown(it.readCents) ?: 0) - it.truthCents) > 1.0 }

    /** Per note: how far the mean of its readings (what the table shows) is from the mean of what was played. */
    val noteMeanErrors: List<Pair<Int, Double>>
        get() = graded.filter { it.clean }.groupBy { it.noteIndex }
            .map { (note, g) -> note to (g.map { it.readCents }.average() - g.map { it.truthCents }.average()) }

    val maxNoteMeanError: Double get() = noteMeanErrors.maxOfOrNull { abs(it.second) } ?: 0.0

    /** Notes long enough to read that produced no saved reading. */
    val missedNotes: List<Int>
        get() = performance.notes.indices.filter { i -> performance.notes[i].seconds >= READABLE_SECONDS && graded.none { it.noteIndex == i } }

    /**
     * Milliseconds from each note's onset to the end of the hop whose decision
     * released its first reading to history: the detector holds a reading
     * pending until the note is confirmed, so this is later than the hop the
     * reading measured.
     */
    val latenciesMs: List<Double>
        get() = performance.notes.indices.mapNotNull { i ->
            graded.firstOrNull { it.noteIndex == i }?.let { (it.savedAt + 1) * hopFrames - performance.onsets[i] }?.let { it * 1000.0 / performance.sampleRate }
        }

    /** Milliseconds from each note's onset to the end of the hop whose decision put it on the readout (its attack). */
    val readoutLatenciesMs: List<Double>
        get() {
            val attacks = decisions.filter { it.attack != null }
            // Notes too short to confirm have no attack of their own; an attack on the
            // same pitch later in the take belongs to a later note.
            return performance.notes.indices.filter { performance.notes[it].seconds >= READABLE_SECONDS }.mapNotNull { i ->
                val onsetHop = performance.onsets[i] / hopFrames
                val endHop = performance.ends[i] / hopFrames
                attacks.firstOrNull { it.hop in onsetHop..endHop && it.attack!!.midiSounding == performance.notes[i].midi }
                    ?.let { ((it.hop + 1) * hopFrames - performance.onsets[i]) * 1000.0 / performance.sampleRate }
            }
        }

    val attacks: Int get() = decisions.count { it.attack != null }

    /** The worst clean readings, for the report. */
    fun worst(n: Int = 3): List<String> = graded.filter { it.clean }.sortedByDescending { abs(it.error) }.take(n).map {
        val note = performance.notes[it.noteIndex]
        val sinceOnset = ((it.hop - 1) * hopFrames - performance.onsets[it.noteIndex]) * 1000.0 / performance.sampleRate
        "%s note %d (%s) %.0f ms in: read %+.2f, played %+.2f".format(title, it.noteIndex, Pitch.label(note.midi), sinceOnset, it.readCents, it.truthCents)
    }

    fun summary(): String = buildString {
        append("| ").append(title)
        append(" | ").append(graded.size).append(" / ").append(graded.count { it.clean })
        append(" | ").append(wrongNotes.size + phantoms.size)
        append(" | ").append("%.2f".format(meanError))
        append(" | ").append("%.2f".format(p95Error))
        append(" | ").append("%.2f".format(maxError))
        append(" | ").append("%.2f".format(maxNoteMeanError))
        append(" | ").append(shownOff)
        append(" | ").append(attacks).append(" / ").append(performance.notes.size)
        append(" | ").append(readoutLatenciesMs.maxOrNull()?.let { "%.0f".format(it) } ?: "-")
        append(" | ").append(latenciesMs.maxOrNull()?.let { "%.0f".format(it) } ?: "-")
        append(" | ").append(missedNotes.size)
        append(" |")
    }

    companion object {
        /** A note this long is always confirmed and read, in every response mode the bench uses. */
        const val READABLE_SECONDS = 0.45

        const val HEADER = "| take | readings / clean | wrong or phantom | mean c | p95 c | max c | worst note mean c | printed cent over 1 c off | attacks / notes | worst readout ms | worst saved ms | missed |\n" +
            "|---|---|---|---|---|---|---|---|---|---|---|---|"
    }
}
