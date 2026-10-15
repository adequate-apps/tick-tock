// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.Voice
import ticktock.core.metro.MetroSetup
import java.util.TreeMap

/**
 * What the sounding tools asked for, kept so the control thread can bring a
 * reopened stream to it: a held pipe keeps sounding at the new rate, the
 * drone continues and the metronome carries on with its setup (OUT-05,
 * OUT-13). The duck is left out on purpose: a reopened stream starts at full
 * gain and the bleed policy ducks again from its own evidence.
 *
 * Two pictures are kept: what the tools want, and what the engine holds.
 * They drift apart while no stream is open, because a command then reaches
 * only the first; a continued engine is brought from the second to the
 * first by [replay]. A4 outlives the tools: it is the player's reference,
 * set once and valid for every stream that follows (TON-01), so [clear]
 * keeps it.
 *
 * Only the control thread touches an instance.
 */
class ToolState {
    private val voices = TreeMap<Int, OutputCommand.VoiceOn>()
    private var a4: OutputCommand.SetA4? = null
    private var metro: MetroSetup? = null
    /** A start the retained engine has not heard, including a new finite run after completion. */
    private var pendingMetroStart = false

    /** The slots the engine has a voice in, and whether its generator runs: what a continued engine still holds. */
    private val engineSlots = sortedSetOf<Int>()
    private var engineMetro = false

    val isMetroRunning: Boolean get() = metro != null

    /**
     * Records the effect of [cmd]; registry and mute commands have no effect
     * here. [delivered] says whether a stream received the command too, so
     * the engine's picture follows it.
     */
    fun apply(cmd: OutputCommand, delivered: Boolean) {
        when (cmd) {
            is OutputCommand.VoiceOn -> {
                voices[cmd.slot] = cmd
                if (delivered) engineSlots += cmd.slot
            }
            is OutputCommand.VoiceOff -> {
                voices.remove(cmd.slot)
                if (delivered) engineSlots -= cmd.slot
            }
            is OutputCommand.VoiceGain -> voices[cmd.slot]?.let { voices[cmd.slot] = it.copy(gain = cmd.gain) }
            is OutputCommand.SetA4 -> a4 = cmd
            is OutputCommand.MetroStart -> {
                setMetro(cmd.setup, delivered)
                pendingMetroStart = !delivered
            }
            is OutputCommand.MetroUpdate -> setMetro(cmd.setup, delivered)
            OutputCommand.MetroStop -> {
                metro = null
                pendingMetroStart = false
                if (delivered) engineMetro = false
            }
            is OutputCommand.Duck,
            is OutputCommand.Register,
            is OutputCommand.Unregister,
            is OutputCommand.Mute,
            -> Unit
        }
    }

    private fun setMetro(setup: MetroSetup, delivered: Boolean) {
        metro = setup
        if (delivered) engineMetro = true
    }

    /**
     * The commands that bring a stream's engine to what the tools want, and
     * the record that it has them. A4 goes first, so the voices strike in
     * tune. Ordinary metronome replay is a MetroUpdate, so a retained engine
     * keeps its beat count and bar (MET-09), and a completed finite run stays
     * finished. An explicit start accepted while parked replays once as a
     * MetroStart with the latest setup, preserving the request for a new run.
     *
     * With [continuing] the engine still holds what it was last sent, so
     * what was switched off meanwhile is switched off there first: the
     * slots released, and the metronome when it was stopped. A fresh engine
     * holds nothing.
     */
    fun replay(continuing: Boolean): List<OutputCommand> {
        val commands = buildList {
            a4?.let { add(it) }
            if (continuing) (engineSlots - voices.keys).forEach { add(OutputCommand.VoiceOff(it)) }
            addAll(voices.values)
            val setup = metro
            if (setup != null) {
                add(if (pendingMetroStart) OutputCommand.MetroStart(setup) else OutputCommand.MetroUpdate(setup))
            } else if (continuing && engineMetro) add(OutputCommand.MetroStop)
        }
        engineSlots.clear()
        engineSlots += voices.keys
        engineMetro = metro != null
        pendingMetroStart = false
        return commands
    }

    /**
     * The MIDI numbers sounding now, for the bleed policy. A root-and-fifth
     * voice sounds two notes and the policy has to know both. A zero target
     * gain keeps the voice available for a later edit but has no sustained leak.
     */
    fun soundingMidi(): IntArray = voices.values.filter { it.gain > 0f }.flatMap { v ->
        if (v.voice == Voice.ROOT_FIFTH) listOf(v.midi, v.midi + FIFTH_SEMITONES) else listOf(v.midi)
    }.distinct().sorted().toIntArray()

    /** Forgets the tools and the engine that held them; A4 stays (TON-01). */
    fun clear() {
        voices.clear()
        metro = null
        pendingMetroStart = false
        engineSlots.clear()
        engineMetro = false
    }

    private companion object {
        const val FIFTH_SEMITONES = 7
    }
}
