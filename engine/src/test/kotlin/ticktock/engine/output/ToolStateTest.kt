// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.Voice
import ticktock.core.metro.Accent
import ticktock.core.metro.ClickSound
import ticktock.core.metro.MetroSetup

class ToolStateTest {
    private val setup = MetroSetup(
        bpm = 120, beatsPerBar = 4, beatUnit = 4, compoundPulse = false,
        accents = listOf(Accent.ACCENT, Accent.NORMAL, Accent.NORMAL, Accent.NORMAL),
        subdivision = 1, click = ClickSound.WOOD, gain = 0.8f, barsOn = 1, barsOff = 0,
    )

    @Test
    fun `OUT-13 the replay rebuilds A4 first, then every voice with its latest gain, then the metronome`() {
        val state = ToolState()
        state.apply(OutputCommand.VoiceOn(slot = 1, midi = 69, voice = Voice.SINE, gain = 0.5f))
        state.apply(OutputCommand.MetroStart(setup))
        state.apply(OutputCommand.VoiceOn(slot = 0, midi = 62, voice = Voice.WARM, gain = 0.7f))
        state.apply(OutputCommand.VoiceGain(slot = 0, gain = 0.2f))
        state.apply(OutputCommand.SetA4(442.0))
        state.apply(OutputCommand.MetroUpdate(setup.copy(bpm = 132)))
        state.apply(OutputCommand.Duck(0.3f))

        assertEquals(
            listOf(
                OutputCommand.SetA4(442.0),
                OutputCommand.VoiceOn(slot = 0, midi = 62, voice = Voice.WARM, gain = 0.2f),
                OutputCommand.VoiceOn(slot = 1, midi = 69, voice = Voice.SINE, gain = 0.5f),
                OutputCommand.MetroStart(setup.copy(bpm = 132)),
            ),
            state.replay(),
        )
    }

    @Test
    fun `a voice switched off and a metronome stopped are not replayed`() {
        val state = ToolState()
        state.apply(OutputCommand.VoiceOn(slot = 0, midi = 62, voice = Voice.WARM, gain = 0.7f))
        state.apply(OutputCommand.MetroStart(setup))
        state.apply(OutputCommand.VoiceOff(slot = 0))
        state.apply(OutputCommand.MetroStop)
        assertEquals(emptyList<OutputCommand>(), state.replay())
        assertFalse(state.isMetroRunning)
    }

    @Test
    fun `registry and mute commands leave the state alone, and clear forgets everything`() {
        val state = ToolState()
        state.apply(OutputCommand.VoiceOn(slot = 0, midi = 62, voice = Voice.WARM, gain = 0.7f))
        state.apply(OutputCommand.Register(OutputTool.DRONE))
        state.apply(OutputCommand.Unregister(OutputTool.DRONE))
        state.apply(OutputCommand.Mute(ticktock.core.contracts.MuteReason.BACKGROUND, active = true))
        assertEquals(1, state.replay().size)
        state.clear()
        assertEquals(emptyList<OutputCommand>(), state.replay())
    }

    @Test
    fun `POL-24 the sounding notes are every voice's note, both notes of a root and fifth, once each, in order`() {
        val state = ToolState()
        state.apply(OutputCommand.VoiceOn(slot = 0, midi = 62, voice = Voice.WARM, gain = 0.7f))
        state.apply(OutputCommand.VoiceOn(slot = 1, midi = 60, voice = Voice.ROOT_FIFTH, gain = 0.7f))
        state.apply(OutputCommand.VoiceOn(slot = 2, midi = 67, voice = Voice.SINE, gain = 0.7f))
        assertArrayEquals(intArrayOf(60, 62, 67), state.soundingMidi())
        state.apply(OutputCommand.VoiceOff(slot = 1))
        assertArrayEquals(intArrayOf(62, 67), state.soundingMidi())
    }

    @Test
    fun `POL-24 the output's sounding notes are a copy, empty until written and empty after clear`() {
        val notes = OutputSoundingNotes()
        assertArrayEquals(intArrayOf(), notes.current())
        notes.update(intArrayOf(62, 69))
        val seen = notes.current()
        seen[0] = 0
        assertArrayEquals(intArrayOf(62, 69), notes.current())
        notes.clear()
        assertTrue(notes.current().isEmpty())
    }
}
