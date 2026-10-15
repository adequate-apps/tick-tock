// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.play

import ticktock.core.music.Pitch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A wind instrument as the microphone hears it: the relative amplitudes of its
 * harmonics, how much breath it leaks, and how far a tongued attack scoops up
 * to pitch. The spectra are shaped after the published averages for each
 * family (a reed's slow roll-off, the clarinet's weak even partials, the
 * flute's near-sine, the trumpet's strong upper partials, a low reed's weak
 * fundamental); they are models, chosen so each one stresses YIN differently.
 */
enum class Instrument(val label: String, val partials: DoubleArray, val breathDb: Double, val scoopCents: Double) {
    ALTO_SAX("alto sax", doubleArrayOf(1.0, 0.9, 0.7, 0.6, 0.5, 0.4, 0.3, 0.25, 0.2, 0.15, 0.1, 0.08), breathDb = -38.0, scoopCents = -18.0),
    CLARINET("clarinet", doubleArrayOf(1.0, 0.05, 0.6, 0.04, 0.4, 0.03, 0.25, 0.02, 0.15, 0.02, 0.08), breathDb = -40.0, scoopCents = -10.0),
    FLUTE("flute", doubleArrayOf(1.0, 0.4, 0.15, 0.08, 0.03), breathDb = -24.0, scoopCents = -8.0),
    TRUMPET("trumpet", doubleArrayOf(0.6, 0.9, 1.0, 0.9, 0.8, 0.65, 0.5, 0.4, 0.3, 0.25, 0.2, 0.15, 0.1), breathDb = -42.0, scoopCents = -25.0),
    BARI_SAX("bari sax", doubleArrayOf(0.15, 1.0, 0.8, 0.6, 0.5, 0.4, 0.3, 0.25, 0.2, 0.15, 0.1), breathDb = -36.0, scoopCents = -20.0),
}

enum class Articulation { TONGUED, SLURRED }

/**
 * One note as written and as played. [cents] is where the player actually
 * sits against equal temperament at the session's A4; [driftCents] is how far
 * the pitch wanders across the note (a sagging long tone); [vibratoCents] is
 * the vibrato's depth either side at [vibratoHz].
 */
data class Note(
    val midi: Int,
    val seconds: Double,
    val cents: Double = 0.0,
    val articulation: Articulation = Articulation.TONGUED,
    val level: Double = 0.25,
    val driftCents: Double = 0.0,
    val vibratoCents: Double = 0.0,
    val vibratoHz: Double = 5.5,
)

/** A score is notes and rests; a rest is a null midi carried as [Rest]. */
sealed interface Event
data class Play(val note: Note) : Event
data class Rest(val seconds: Double) : Event

/** The room and the microphone around the player. */
data class Room(
    /** White noise floor under the player, in dB relative to full scale RMS. */
    val noiseDb: Double = -70.0,
    /** Mains hum at 60 Hz and its first harmonics, dBFS; null for none. */
    val humDb: Double? = null,
    /** Reverb level against the dry sound in dB; null for a dry booth. */
    val reverbDb: Double? = -16.0,
    val reverbSeconds: Double = 0.4,
)

/**
 * The rendered take, with its ground truth: for every sample, the fundamental
 * the player is sounding (0 in a rest) and which note of the score it belongs
 * to (-1 in a rest).
 */
class Performance(
    val sampleRate: Int,
    val a4Hz: Double,
    val samples: FloatArray,
    val truthHz: DoubleArray,
    val noteAt: IntArray,
    val notes: List<Note>,
    /** First sample of each note, in score order. */
    val onsets: IntArray,
    /** First sample after each note's sustain: its release, or the next note. */
    val ends: IntArray,
)

/**
 * Renders a score the way a player would sound it. Every harmonic follows one
 * continuously integrated phase, so slurs glide without clicks and vibrato is
 * true frequency modulation; the truth array records the exact fundamental the
 * oscillators run at, so every reading can be graded against what was played.
 */
class Performer(private val instrument: Instrument, private val sampleRate: Int, private val a4Hz: Double, private val seed: Int = 1) {
    fun render(score: List<Event>, room: Room = Room()): Performance {
        val total = score.sumOf { frames(it) }
        val out = DoubleArray(total)
        val truth = DoubleArray(total)
        val noteAt = IntArray(total) { -1 }
        val notes = score.filterIsInstance<Play>().map { it.note }
        val onsets = IntArray(notes.size)
        val ends = IntArray(notes.size)
        val random = Random(seed)
        val phases = DoubleArray(instrument.partials.size) { random.nextDouble() * 2 * PI }
        val norm = 1.0 / instrument.partials.sum()
        val breath = 10.0.pow(instrument.breathDb / 20.0)
        var cursor = 0
        var noteIndex = 0
        var previousHz = 0.0
        var level = 0.0
        val dt = 1.0 / sampleRate

        for (event in score) {
            val n = frames(event)
            if (event is Rest) {
                // The tone dies away over about 50 ms, then silence. While it is still
                // audible it is still the last note, and the truth says so.
                val audible = level * 1e-2
                for (i in 0 until n) {
                    level *= exp(-dt / 0.012)
                    out[cursor + i] = level * oscillate(phases, previousHz, norm)
                    if (level > audible && noteIndex > 0 && previousHz > 0.0) {
                        truth[cursor + i] = previousHz
                        noteAt[cursor + i] = noteIndex - 1
                    }
                }
                cursor += n
                previousHz = 0.0
                continue
            }
            val note = (event as Play).note
            onsets[noteIndex] = cursor
            val targetHz = Pitch.hzFromMidi(note.midi + note.cents / 100.0, a4Hz)
            val slur = note.articulation == Articulation.SLURRED && previousHz > 0.0
            for (i in 0 until n) {
                val t = i * dt
                val progress = t / note.seconds
                // Where the player is aiming now: the note's own pitch, its drift and vibrato.
                var cents = note.driftCents * progress + note.vibratoCents * sin(2 * PI * note.vibratoHz * t)
                // A tongued note scoops up from below and settles in about 40 ms; a slur glides over 20 ms.
                if (!slur) cents += instrument.scoopCents * exp(-t / 0.04)
                var hz = targetHz * 2.0.pow(cents / 1200.0)
                if (slur && t < 0.02) {
                    val f = t / 0.02
                    hz = previousHz.pow(1 - f) * hz.pow(f)
                }
                // Tongued: a 25 ms rise; slurred: the level moves to the new note's over 30 ms.
                val target = note.level
                level = if (!slur) target * (1 - exp(-t / 0.008)) else level + (target - level) * (dt / 0.03).coerceAtMost(1.0)
                var sample = level * oscillate(phases, hz, norm)
                // Breath under the tone, and a short burst of air at a tongued onset.
                val air = (random.nextDouble() * 2 - 1) * level * breath
                sample += air + if (!slur && t < 0.015) (random.nextDouble() * 2 - 1) * level * 0.12 else 0.0
                out[cursor + i] = sample
                truth[cursor + i] = hz
                noteAt[cursor + i] = noteIndex
            }
            previousHz = targetHz * 2.0.pow((note.driftCents) / 1200.0)
            cursor += n
            ends[noteIndex] = cursor
            noteIndex++
        }

        room.reverbDb?.let { reverb(out, 10.0.pow(it / 20.0), room.reverbSeconds) }
        val noise = 10.0.pow(room.noiseDb / 20.0) * kotlin.math.sqrt(3.0)
        val hum = room.humDb?.let { 10.0.pow(it / 20.0) }
        val samples = FloatArray(total) { i ->
            var s = out[i] + (random.nextDouble() * 2 - 1) * noise
            if (hum != null) s += hum * (sin(2 * PI * 60.0 * i / sampleRate) + 0.5 * sin(2 * PI * 120.0 * i / sampleRate))
            s.toFloat()
        }
        return Performance(sampleRate, a4Hz, samples, truth, noteAt, notes, onsets, ends)
    }

    private fun frames(event: Event): Int = ((if (event is Play) event.note.seconds else (event as Rest).seconds) * sampleRate).toInt()

    /** One sample of the instrument at [hz]; advances every partial's phase. */
    private fun oscillate(phases: DoubleArray, hz: Double, norm: Double): Double {
        if (hz <= 0.0) return 0.0
        val nyquist = sampleRate / 2.0
        var s = 0.0
        for (p in phases.indices) {
            val partialHz = hz * (p + 1)
            if (partialHz >= nyquist) continue
            s += instrument.partials[p] * sin(phases[p])
            phases[p] += 2 * PI * partialHz / sampleRate
            if (phases[p] > 2 * PI) phases[p] -= 2 * PI
        }
        return s * norm
    }

    /**
     * A small hall: four parallel feedback combs into two allpasses (the
     * Schroeder layout), mixed in at [wet]. Reflections of the previous note
     * under the next one are what make a real room hard for a tuner.
     */
    private fun reverb(signal: DoubleArray, wet: Double, seconds: Double) {
        val combs = listOf(0.0297, 0.0371, 0.0411, 0.0437).map { (it * sampleRate).toInt() }
        val wetOut = DoubleArray(signal.size)
        for (delay in combs) {
            val gain = 10.0.pow(-3.0 * delay / (seconds * sampleRate))
            val buffer = DoubleArray(delay)
            var k = 0
            for (i in signal.indices) {
                val y = buffer[k]
                buffer[k] = signal[i] + y * gain
                wetOut[i] += y
                k = (k + 1) % delay
            }
        }
        for (delay in listOf((0.005 * sampleRate).toInt(), (0.0017 * sampleRate).toInt())) {
            val buffer = DoubleArray(delay)
            var k = 0
            for (i in wetOut.indices) {
                val x = wetOut[i]
                val y = buffer[k] - 0.7 * x
                buffer[k] = x + 0.7 * y
                wetOut[i] = y
                k = (k + 1) % delay
            }
        }
        val scale = wet / combs.size
        for (i in signal.indices) signal[i] += wetOut[i] * scale
    }
}
