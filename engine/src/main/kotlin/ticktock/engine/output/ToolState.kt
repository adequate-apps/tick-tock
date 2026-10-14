// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.Voice
import ticktock.core.metro.MetroSetup
import java.util.TreeMap

/**
 * What the sounding tools asked for, kept so the control thread can replay it
 * into a reopened stream: a held pipe keeps sounding at the new rate, the
 * drone continues and the metronome restarts with its setup (OUT-05,
 * OUT-13). The duck is left out on purpose: a reopened stream starts at full
 * gain and the bleed policy ducks again from its own evidence.
 *
 * Only the control thread touches an instance.
 */
class ToolState {
    private val voices = TreeMap<Int, OutputCommand.VoiceOn>()
    private var a4: OutputCommand.SetA4? = null
    private var metro: MetroSetup? = null

    val isMetroRunning: Boolean get() = metro != null

    /** Records the effect of [cmd]; registry and mute commands have no effect here. */
    fun apply(cmd: OutputCommand) {
        when (cmd) {
            is OutputCommand.VoiceOn -> voices[cmd.slot] = cmd
            is OutputCommand.VoiceOff -> voices.remove(cmd.slot)
            is OutputCommand.VoiceGain -> voices[cmd.slot]?.let { voices[cmd.slot] = it.copy(gain = cmd.gain) }
            is OutputCommand.SetA4 -> a4 = cmd
            is OutputCommand.MetroStart -> metro = cmd.setup
            is OutputCommand.MetroUpdate -> metro = cmd.setup
            OutputCommand.MetroStop -> metro = null
            is OutputCommand.Duck,
            is OutputCommand.Register,
            is OutputCommand.Unregister,
            is OutputCommand.Mute,
            -> Unit
        }
    }

    /** The commands that rebuild this state in a fresh stream: A4 first, so the voices strike in tune. */
    fun replay(): List<OutputCommand> = buildList {
        a4?.let { add(it) }
        addAll(voices.values)
        metro?.let { add(OutputCommand.MetroStart(it)) }
    }

    /**
     * The MIDI numbers sounding now, for the bleed policy. A root-and-fifth
     * voice sounds two notes and the policy has to know both.
     */
    fun soundingMidi(): IntArray = voices.values.flatMap { v ->
        if (v.voice == Voice.ROOT_FIFTH) listOf(v.midi, v.midi + FIFTH_SEMITONES) else listOf(v.midi)
    }.distinct().sorted().toIntArray()

    fun clear() {
        voices.clear()
        a4 = null
        metro = null
    }

    private companion object {
        const val FIFTH_SEMITONES = 7
    }
}
