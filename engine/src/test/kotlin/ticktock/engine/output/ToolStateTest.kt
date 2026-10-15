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
    private val warm = OutputCommand.VoiceOn(slot = 0, midi = 62, voice = Voice.WARM, gain = 0.7f)
    private val sine = OutputCommand.VoiceOn(slot = 1, midi = 69, voice = Voice.SINE, gain = 0.5f)

    /** Commands a stream received as well. */
    private fun ToolState.sent(vararg cmds: OutputCommand) = cmds.forEach { apply(it, delivered = true) }

    /** Commands that arrived while no stream was open. */
    private fun ToolState.kept(vararg cmds: OutputCommand) = cmds.forEach { apply(it, delivered = false) }

    @Test
    fun `OUT-13 and MET-09 the replay rebuilds A4 first, then every voice with its latest gain, then the metronome as an edit`() {
        val state = ToolState()
        state.sent(sine, OutputCommand.MetroStart(setup), warm, OutputCommand.VoiceGain(slot = 0, gain = 0.2f))
        state.sent(OutputCommand.SetA4(442.0), OutputCommand.MetroUpdate(setup.copy(bpm = 132)), OutputCommand.Duck(0.3f))

        val expected = listOf(
            OutputCommand.SetA4(442.0),
            warm.copy(gain = 0.2f),
            sine,
            OutputCommand.MetroUpdate(setup.copy(bpm = 132)),
        )
        assertEquals(expected, state.replay(continuing = true))
        assertEquals(expected, state.replay(continuing = false))
    }

    @Test
    fun `a continued replay switches off what was switched off while no stream was open, and a fresh one has nothing to switch off`() {
        val state = ToolState()
        state.sent(warm, sine, OutputCommand.MetroStart(setup))
        state.kept(OutputCommand.VoiceOff(slot = 0), OutputCommand.MetroStop)
        // A slot the engine never had has nothing to switch off.
        state.kept(OutputCommand.VoiceOff(slot = 3))

        assertEquals(listOf(OutputCommand.VoiceOff(slot = 0), sine, OutputCommand.MetroStop), state.replay(continuing = true))
        // The engine is up to date now: the same replay again carries only what sounds.
        assertEquals(listOf<OutputCommand>(sine), state.replay(continuing = true))

        state.kept(OutputCommand.VoiceOff(slot = 1))
        assertEquals(emptyList<OutputCommand>(), state.replay(continuing = false))
    }

    @Test
    fun `what a stream received needs no replaying when it ends, because an engine that heard the stop holds nothing`() {
        val state = ToolState()
        state.sent(warm, OutputCommand.MetroStart(setup), OutputCommand.VoiceOff(slot = 0), OutputCommand.MetroStop)
        assertEquals(emptyList<OutputCommand>(), state.replay(continuing = true))
        assertFalse(state.isMetroRunning)
    }

    @Test
    fun `registry and mute commands leave the state alone, and clear forgets the tools and the engine`() {
        val state = ToolState()
        state.sent(warm, OutputCommand.MetroStart(setup))
        state.sent(OutputCommand.Register(OutputTool.DRONE), OutputCommand.Unregister(OutputTool.DRONE))
        state.sent(OutputCommand.Mute(ticktock.core.contracts.MuteReason.BACKGROUND, active = true))
        assertEquals(listOf(warm, OutputCommand.MetroUpdate(setup)), state.replay(continuing = true))
        state.clear()
        assertEquals(emptyList<OutputCommand>(), state.replay(continuing = true))
        assertEquals(emptyList<OutputCommand>(), state.replay(continuing = false))
    }

    @Test
    fun `TON-01 A4 outlives the tools, clear keeps it and every replay starts with it`() {
        val state = ToolState()
        state.sent(OutputCommand.SetA4(442.0), warm)
        state.clear()
        assertEquals(listOf<OutputCommand>(OutputCommand.SetA4(442.0)), state.replay(continuing = false))
        state.kept(OutputCommand.SetA4(415.0))
        assertEquals(listOf<OutputCommand>(OutputCommand.SetA4(415.0)), state.replay(continuing = false))
    }

    @Test
    fun `BTC-01 a queued explicit restart keeps its latest setup and replays only once`() {
        val state = ToolState()
        val completed = setup.copy(bpm = 100, beatLimit = 8, generation = 20)
        state.sent(OutputCommand.MetroStart(completed))
        val requested = completed.copy(generation = 21)
        val edited = requested.copy(gain = 0.6f)
        state.kept(OutputCommand.MetroStart(requested), OutputCommand.MetroUpdate(edited))
        assertEquals(listOf(OutputCommand.MetroStart(edited)), state.replay(continuing = true))
        assertEquals(listOf(OutputCommand.MetroUpdate(edited)), state.replay(continuing = true))

        state.kept(OutputCommand.MetroStart(requested))
        state.sent(OutputCommand.MetroStart(edited))
        assertEquals(listOf(OutputCommand.MetroUpdate(edited)), state.replay(continuing = true))

        state.kept(OutputCommand.MetroStart(requested), OutputCommand.MetroStop)
        assertEquals(listOf<OutputCommand>(OutputCommand.MetroStop), state.replay(continuing = true))
        state.kept(OutputCommand.MetroStart(requested))
        state.clear()
        assertEquals(emptyList<OutputCommand>(), state.replay(continuing = true))
    }

    @Test
    fun `POL-24 the sounding notes are every voice's note, both notes of a root and fifth, once each, in order`() {
        val state = ToolState()
        state.sent(warm)
        state.sent(OutputCommand.VoiceOn(slot = 1, midi = 60, voice = Voice.ROOT_FIFTH, gain = 0.7f))
        state.sent(OutputCommand.VoiceOn(slot = 2, midi = 67, voice = Voice.SINE, gain = 0.7f))
        assertArrayEquals(intArrayOf(60, 62, 67), state.soundingMidi())
        state.sent(OutputCommand.VoiceOff(slot = 1))
        assertArrayEquals(intArrayOf(62, 67), state.soundingMidi())
    }

    @Test
    fun `POL-24 zero gain voices keep their intent but contribute no sounding notes`() {
        val state = ToolState()
        val fifth = sine.copy(voice = Voice.ROOT_FIFTH, gain = 0f)
        state.sent(warm, fifth)
        assertArrayEquals(intArrayOf(62), state.soundingMidi())
        assertTrue(state.replay(continuing = true).contains(fifth))
        state.sent(OutputCommand.VoiceGain(fifth.slot, 0.5f))
        assertArrayEquals(intArrayOf(62, 69, 76), state.soundingMidi())
        state.sent(OutputCommand.VoiceGain(fifth.slot, 0f), OutputCommand.VoiceGain(warm.slot, 0f))
        assertArrayEquals(intArrayOf(), state.soundingMidi())
        assertEquals(2, state.replay(continuing = true).filterIsInstance<OutputCommand.VoiceOn>().size)
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
