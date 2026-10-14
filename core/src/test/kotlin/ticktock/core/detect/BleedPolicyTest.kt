// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.fakes.FakeSoundingNotes
import ticktock.core.music.Pitch

/**
 * Spec 2.6 on scripted estimates, the U half of POL-24 to POL-30. The N half,
 * the engine's own drone rendered into fixtures, lands with the engine.
 */
class BleedPolicyTest {
    private val d4 = 62
    private val f4 = 65
    private val a4 = 69
    private val level = Drive.TONE_RMS

    private fun drive(vararg sounding: Int, duckable: Boolean = true): Pair<Drive, FakeSoundingNotes> {
        val notes = FakeSoundingNotes(*sounding)
        return Drive(Detector(ResponseMode.NORMAL, sounding = notes, duckable = duckable)) to notes
    }

    /**
     * The microphone hearing only the output: the leak's level follows the
     * gain the policy asks for, as the engine's duck would make it. The ramp
     * is treated as instant, which is the harder case for the policy.
     */
    private fun leak(drive: Drive, midi: Int, hops: Int): List<Decision> {
        var gain = BleedPolicy.FULL_GAIN.toDouble()
        val out = ArrayList<Decision>(hops)
        repeat(hops) {
            val decision = drive.tone(Pitch.hzFromMidi(midi, Pitch.A4_DEFAULT_HZ), 1, rms = level * gain).single()
            decision.duck?.let { gain = it.toDouble() }
            out += decision
        }
        return out
    }

    @Test
    fun `POL-24 the drone alone saves nothing and shows no note`() {
        val (drive, _) = drive(d4)
        val decisions = leak(drive, d4, Hop.count(10.0))
        assertEquals(0, drive.saved.size)
        assertTrue(decisions.all { it.incumbent == null })
        assertTrue(decisions.all { it.hopClass == HopClass.SKIP && it.skip == SkipReason.BLEED })
        assertNull(drive.detector.shownNote)
        assertEquals(listOf(BleedPolicy.DUCK_FLOOR), decisions.mapNotNull { it.duck })
    }

    @Test
    fun `POL-25 a bleed hop is skipped and the held note and its pending readings survive`() {
        val (drive, _) = drive(d4)
        drive.note(f4, 30)
        assertEquals(f4, drive.detector.shownNote)
        val pending = drive.detector.pendingCount
        val savedBefore = drive.saved.size

        val bleed = leak(drive, d4, 20)
        assertTrue(bleed.all { it.hopClass == HopClass.SKIP })
        assertTrue(bleed.all { it.incumbent == f4 })
        assertEquals(f4, drive.detector.shownNote)
        assertEquals(pending, drive.detector.pendingCount)
        assertEquals(savedBefore, drive.saved.size)
        assertTrue(drive.releases.isEmpty())

        // The held note carries on without a new attack.
        drive.note(f4, 10)
        assertEquals(1, drive.attacks.size)
        assertTrue(drive.saved.size > savedBefore)
    }

    @Test
    fun `POL-26 three suspect hops duck to the floor and three clear hops release it`() {
        val (drive, _) = drive(d4)
        val suspect = drive.note(d4, BleedPolicy.SUSPECT_HOPS)
        assertEquals(listOf(null, null, BleedPolicy.DUCK_FLOOR), suspect.map { it.duck })
        assertTrue(drive.detector.bleedState.ducked)

        val clear = drive.note(f4, BleedPolicy.CLEAR_HOPS)
        assertEquals(listOf(null, null, BleedPolicy.FULL_GAIN), clear.map { it.duck })
        assertEquals(BleedState.IDLE, drive.detector.bleedState)
    }

    @Test
    fun `POL-26 two suspect hops then a clear one never duck`() {
        val (drive, _) = drive(d4)
        drive.note(d4, 2)
        drive.note(f4, 1)
        drive.note(d4, 2)
        assertTrue(drive.decisions.none { it.duck != null })
    }

    @Test
    fun `POL-27 a level that holds up under the duck is the player, and the note is re-admitted`() {
        val (drive, _) = drive(d4)
        val hz = Pitch.hzFromMidi(d4, Pitch.A4_DEFAULT_HZ)
        drive.tone(hz, BleedPolicy.SUSPECT_HOPS, rms = level)
        val preDuck = drive.detector.bleedState.preDuckLevel
        assertEquals(level, preDuck, 0.0)

        // The ramp is still landing: nothing is judged until the hop it reaches the floor.
        drive.tone(hz, BleedPolicy.RAMP_HOPS - 1, rms = level)
        assertEquals(0, drive.detector.bleedState.strongHops)

        val unison = drive.tone(hz, BleedPolicy.UNISON_HOPS, rms = BleedPolicy.UNISON_RATIO * preDuck)
        assertTrue(unison.dropLast(1).all { it.hopClass == HopClass.SKIP })
        assertEquals(HopClass.VOTE, unison.last().hopClass)
        assertEquals(d4, drive.detector.bleedState.admitted)

        drive.tone(hz, 20, rms = BleedPolicy.UNISON_RATIO * preDuck)
        val saved = drive.saved
        assertTrue(saved.isNotEmpty())
        assertTrue(saved.all { it.midiSounding == d4 })
        assertEquals(d4, drive.detector.shownNote)
    }

    @Test
    fun `POL-27 a dip below the ratio restarts the unison count`() {
        val (drive, _) = drive(d4)
        val hz = Pitch.hzFromMidi(d4, Pitch.A4_DEFAULT_HZ)
        drive.tone(hz, BleedPolicy.SUSPECT_HOPS + BleedPolicy.RAMP_HOPS - 1, rms = level)
        drive.tone(hz, BleedPolicy.UNISON_HOPS - 1, rms = level)
        assertEquals(BleedPolicy.UNISON_HOPS - 1, drive.detector.bleedState.strongHops)
        drive.tone(hz, 1, rms = level * 0.5)
        assertEquals(0, drive.detector.bleedState.strongHops)
        assertNull(drive.detector.bleedState.admitted)
        drive.tone(hz, BleedPolicy.UNISON_HOPS - 1, rms = level)
        assertNull(drive.detector.bleedState.admitted)
        drive.tone(hz, 1, rms = level)
        assertEquals(d4, drive.detector.bleedState.admitted)
    }

    @Test
    fun `POL-28 leakage falls with the duck and is never re-admitted`() {
        val (drive, _) = drive(d4)
        val hz = Pitch.hzFromMidi(d4, Pitch.A4_DEFAULT_HZ)
        drive.tone(hz, BleedPolicy.SUSPECT_HOPS, rms = level)
        // After the duck the leak sits at the floor, a third of what it was.
        val decisions = drive.tone(hz, Hop.count(10.0), rms = level * BleedPolicy.DUCK_FLOOR)
        assertTrue(decisions.all { it.hopClass == HopClass.SKIP })
        assertNull(drive.detector.bleedState.admitted)
        assertEquals(0, drive.saved.size)
        assertNull(drive.detector.shownNote)
    }

    @Test
    fun `POL-29 an output that cannot duck never re-admits its note`() {
        val (drive, _) = drive(d4, duckable = false)
        val decisions = drive.note(d4, Hop.count(10.0))
        assertTrue(decisions.none { it.duck != null })
        assertTrue(decisions.all { it.hopClass == HopClass.SKIP })
        assertEquals(0, drive.saved.size)
        assertNull(drive.detector.bleedState.admitted)
        assertTrue(drive.detector.bleedState.ducked.not())
    }

    @Test
    fun `POL-30 a pitch pipe is the same consumer as the drone`() {
        // The policy sees sounding notes, whatever sounds them; two at once
        // are both suspects and either can be admitted on its own evidence.
        val (drive, _) = drive(d4, a4)
        val hz = Pitch.hzFromMidi(a4, Pitch.A4_DEFAULT_HZ)
        val suspect = drive.tone(hz, BleedPolicy.SUSPECT_HOPS, rms = level)
        assertEquals(BleedPolicy.DUCK_FLOOR, suspect.last().duck)
        drive.tone(hz, BleedPolicy.RAMP_HOPS - 1, rms = level)
        drive.tone(hz, BleedPolicy.UNISON_HOPS, rms = level)
        assertEquals(a4, drive.detector.bleedState.admitted)
        // The other pipe's note is still excluded: the evidence was for A4, and
        // D4 has to earn its own five hops.
        val other = drive.note(d4, BleedPolicy.UNISON_HOPS - 1)
        assertTrue(other.all { it.hopClass == HopClass.SKIP })
        assertEquals(BleedPolicy.UNISON_HOPS - 1, drive.detector.bleedState.strongHops)
        assertEquals(d4, drive.detector.bleedState.strongNote)
    }

    @Test
    fun `the duck is restored when the output stops sounding`() {
        val (drive, notes) = drive(d4)
        drive.note(d4, BleedPolicy.SUSPECT_HOPS)
        assertTrue(drive.detector.bleedState.ducked)
        notes.notes = IntArray(0)
        val next = drive.note(d4, 1).single()
        assertEquals(BleedPolicy.FULL_GAIN, next.duck)
        assertEquals(HopClass.VOTE, next.hopClass)
        assertEquals(BleedState.IDLE, drive.detector.bleedState)
    }

    @Test
    fun `a held note that the output starts sounding leaves the readout until it is proven`() {
        val (drive, notes) = drive()
        drive.note(d4, 30)
        assertEquals(d4, drive.detector.shownNote)
        notes.notes = intArrayOf(d4)
        val first = drive.note(d4, 1).single()
        assertEquals(HopClass.SKIP, first.hopClass)
        assertEquals(ReleaseCause.BLEED, first.release!!.cause)
        assertNull(first.incumbent)
        assertNull(drive.detector.shownNote)
    }

    @Test
    fun `the policy is a pure function of its state`() {
        val sounding = intArrayOf(d4)
        val a = BleedPolicy.step(BleedState.IDLE, sounding, d4, 0.1, duckable = true)
        val b = BleedPolicy.step(BleedState.IDLE, sounding, d4, 0.1, duckable = true)
        assertEquals(a, b)
        assertEquals(BleedState.IDLE, BleedState())
        assertTrue(a.exclude)
        assertEquals(1, a.state.suspectHops)
        // A rejected hop (no candidate) is a clear hop.
        val clear = BleedPolicy.step(a.state, sounding, null, 0.0, duckable = true)
        assertEquals(0, clear.state.suspectHops)
        assertEquals(1, clear.state.clearHops)
        assertEquals(2, BleedPolicy.RAMP_HOPS)
        assertEquals(0.65, BleedPolicy.UNISON_RATIO, 1e-12)
    }
}
