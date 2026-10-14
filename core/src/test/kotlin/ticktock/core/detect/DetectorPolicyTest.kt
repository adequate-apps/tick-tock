// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.music.Pitch

/**
 * Spec 2.1 to 2.3 on scripted estimates: the detector is pure, so every
 * scenario is a list of hops and the decisions they produce.
 */
class DetectorPolicyTest {
    private val a4 = 69
    private val bb4 = 70

    @Test
    fun `POL-01 the first saved reading lands at confirm plus two edges in every mode`() {
        for (mode in ResponseMode.entries) {
            val drive = Drive(Detector(mode))
            val params = ResponseModeTable.of(mode)
            drive.silence(5)
            val start = drive.hop
            drive.note(a4, 40)
            val first = drive.decisions.first { it.saved != null }
            val latency = first.hop - start
            assertTrue("$mode: first reading at hop $latency, expected ${params.firstReadingHops} ±1", kotlin.math.abs(latency - params.firstReadingHops) <= 1)
            assertEquals(a4, first.saved!!.midiSounding)
        }
    }

    @Test
    fun `POL-02 a legato change shows within the budget and the readout is never blank`() {
        val drive = Drive(Detector(ResponseMode.NORMAL))
        drive.note(a4, Hop.count(2.0))
        assertEquals(a4, drive.detector.shownNote)
        val change = drive.hop
        val after = drive.note(bb4, 20)
        val shown = after.first { it.incumbent == bb4 }
        val latencyMillis = (shown.hop - change + 1) * Hop.MILLIS
        assertTrue("new note shown after $latencyMillis ms", latencyMillis <= 372.0)
        for (d in after) assertNotNull("blank readout at hop ${d.hop}", d.incumbent)
        // The readout moved straight from one note to the other.
        assertEquals(listOf(a4, bb4), after.map { it.incumbent }.distinct())
    }

    @Test
    fun `POL-03 votes older than the window do not exist`() {
        val drive = Drive(Detector(ResponseMode.NORMAL))
        val window = drive.detector.params.voteWindow
        drive.note(a4, 100)
        drive.note(bb4, window)
        assertEquals(bb4, drive.detector.shownNote)
        // Two hops of A are below confirm; a long memory would hand A the vote back.
        drive.note(a4, 2)
        assertEquals(bb4, drive.detector.shownNote)
        drive.note(bb4, 1)
        assertEquals(bb4, drive.detector.shownNote)
    }

    @Test
    fun `POL-05 a mode change forgets the held note and its pending readings`() {
        val drive = Drive(Detector(ResponseMode.NORMAL))
        drive.note(a4, 30)
        assertEquals(a4, drive.detector.shownNote)
        assertEquals(2, drive.detector.pendingCount)

        drive.detector.setMode(ResponseMode.FAST)
        assertNull(drive.detector.shownNote)
        assertEquals(0, drive.detector.pendingCount)
        assertEquals(ResponseMode.FAST, drive.detector.mode)

        // The same note has to win again, on Fast's terms.
        val resume = drive.hop
        drive.note(a4, 20)
        val first = drive.decisions.first { it.hop >= resume && it.saved != null }
        assertEquals(ResponseModeTable.of(ResponseMode.FAST).firstReadingHops.toLong(), first.hop - resume)
        assertEquals(2, drive.attacks.size)
    }

    @Test
    fun `POL-06 the attack edge is shown and never saved`() {
        val drive = Drive(Detector(ResponseMode.NORMAL))
        val params = drive.detector.params
        drive.note(a4, 30)
        val attack = drive.attacks.single()
        assertEquals((params.confirm - 1).toLong(), attack.hop)
        assertEquals(a4, attack.midiSounding)

        val edgeHops = (attack.hop..attack.hop + params.edge).toList()
        for (hop in edgeHops) {
            val decision = drive.decisions[hop.toInt()]
            assertNotNull("edge hop $hop is shown", decision.display)
            assertEquals(a4, decision.incumbent)
        }
        val savedHops = drive.saved.map { it.hop }
        assertTrue("edge hops saved: ${savedHops.filter { it in edgeHops }}", savedHops.none { it in edgeHops })
        assertEquals(attack.hop + params.edge + 1, savedHops.first())
        // Readings before the win were votes, never readings.
        assertTrue(savedHops.none { it < attack.hop })
    }

    @Test
    fun `POL-07 the last edge readings of a note that ends in silence are discarded`() {
        val drive = Drive(Detector(ResponseMode.NORMAL))
        val edge = drive.detector.params.edge
        drive.note(a4, 30)
        val lastToneHop = drive.hop - 1
        drive.silence(10)

        val release = drive.releases.single()
        assertEquals(ReleaseCause.SILENCE, release.cause)
        assertEquals(a4, release.midiSounding)
        assertEquals(edge, release.discardedPending)
        assertEquals(lastToneHop - edge, drive.saved.last().hop)
        assertNull(drive.detector.shownNote)
        assertEquals(0, drive.detector.pendingCount)
    }

    @Test
    fun `POL-07 a note that changes to another loses its tail too`() {
        val drive = Drive(Detector(ResponseMode.NORMAL))
        val edge = drive.detector.params.edge
        drive.note(a4, 30)
        val lastA = drive.hop - 1
        drive.note(bb4, 30)
        val release = drive.releases.single()
        assertEquals(ReleaseCause.NOTE_CHANGE, release.cause)
        assertEquals(edge, release.discardedPending)
        assertEquals(lastA - edge, drive.saved.last { it.midiSounding == a4 }.hop)
    }

    @Test
    fun `POL-08 a re-attack of the same note drops its edge again`() {
        val drive = Drive(Detector(ResponseMode.NORMAL))
        val params = drive.detector.params
        drive.note(a4, 30)
        drive.silence(10)
        val again = drive.hop
        drive.note(a4, 30)

        assertEquals(2, drive.attacks.size)
        val second = drive.attacks[1]
        assertEquals(again + params.confirm - 1, second.hop)
        val secondSaved = drive.saved.filter { it.hop >= again }.map { it.hop }
        assertEquals(second.hop + params.edge + 1, secondSaved.first())
        // Every hop between the re-attack and its first saved reading went to the readout only.
        assertTrue(secondSaved.none { it < second.hop + params.edge + 1 })
    }

    /**
     * POL-07 and POL-08 on a gap shorter than the vote window. The release
     * edge is the mode's allowance for a transient at the end of a note; a
     * silence that outlasts it is the end of the note, so the tail is
     * discarded and the next sound is a re-attack confirmed on its own hops,
     * with its own edge dropped. A gap inside the allowance is a glitch the
     * held note rides out.
     */
    @Test
    fun `POL-08 a silence longer than the release edge ends the note and the same note re-attacks, at every gap under the window`() {
        for (mode in ResponseMode.entries) {
            val params = ResponseModeTable.of(mode)
            for (gap in 1 until params.voteWindow) {
                val label = "$mode, gap of $gap hops"
                val drive = Drive(Detector(mode))
                drive.note(a4, 30)
                val lastToneHop = drive.hop - 1
                val savedBefore = drive.saved.size
                drive.silence(gap)
                val again = drive.hop
                drive.note(a4, 30)

                if (gap <= params.edge) {
                    assertEquals(label, 1, drive.attacks.size)
                    assertTrue(label, drive.releases.isEmpty())
                    continue
                }
                val release = drive.releases.single()
                assertEquals(label, ReleaseCause.SILENCE, release.cause)
                assertEquals(label, params.edge, release.discardedPending)
                assertEquals("$label: released on the first silent hop past the edge", lastToneHop + params.edge + 1, release.hop)
                assertEquals("$label: the tail before the gap", lastToneHop - params.edge, drive.saved[savedBefore - 1].hop)
                assertTrue("$label: a reading saved across the gap", drive.saved.none { it.hop in (lastToneHop - params.edge + 1) until again })
                assertEquals(label, 2, drive.attacks.size)
                val second = drive.attacks[1]
                assertEquals("$label: the re-attack confirms on its own hops", again + params.confirm - 1, second.hop)
                val secondSaved = drive.saved.filter { it.hop >= again }.map { it.hop }
                assertEquals("$label: the re-attack's edge is dropped", second.hop + params.edge + 1, secondSaved.first())
            }
        }
    }

    /**
     * What the gap rule protects: a note stops with a flat droop, a tongued
     * gap shorter than the window follows, and the same note scoops back in
     * sharp. Neither the droop nor the scoop is a reading of the held tone.
     */
    @Test
    fun `POL-07 the release droop and the re-attack scoop around a short gap never reach history`() {
        val drive = Drive(Detector(ResponseMode.NORMAL))
        val params = drive.detector.params
        fun detuned(cents: Double, hops: Int) = drive.tone(Pitch.hzFromMidi(a4 + cents / 100.0, Pitch.A4_DEFAULT_HZ), hops)
        drive.note(a4, 30)
        detuned(-25.0, params.edge)
        drive.silence(params.edge + 2)
        detuned(30.0, params.confirm + params.edge)
        drive.note(a4, 30)

        val offPitch = drive.saved.filter { kotlin.math.abs(it.cents) > 1.0 }.map { "${it.hop}: ${it.cents} c" }
        assertEquals("off-pitch readings saved", emptyList<String>(), offPitch)
        assertEquals(2, drive.attacks.size)
        assertEquals(30.0, drive.attacks[1].landingCents, 1e-9)
        val release = drive.releases.single()
        assertEquals(ReleaseCause.SILENCE, release.cause)
        assertEquals(params.edge, release.discardedPending)
    }

    @Test
    fun `POL-10 noise is rejected on every path and shows no note`() {
        for (mode in ResponseMode.entries) {
            val drive = Drive(Detector(mode))
            // -20 dBFS is 0.1 linear.
            val decisions = drive.noise(Hop.count(10.0), rms = 0.1)
            assertEquals(mode.toString(), 0, drive.saved.size)
            assertTrue("$mode: a note showed", decisions.all { it.incumbent == null })
            assertTrue("$mode: a noise hop was not rejected", decisions.all { it.hopClass == HopClass.REJECT && it.reject == RejectReason.APERIODIC })
        }
    }

    @Test
    fun `POL-10 the ceiling is inclusive and a hair above it is a reject`() {
        for (mode in ResponseMode.entries) {
            val ceiling = ResponseModeTable.of(mode).aperiodicityCeiling
            val drive = Drive(Detector(mode))
            assertEquals(HopClass.VOTE, drive.tone(440.0, 1, aperiodicity = ceiling).single().hopClass)
            assertEquals(RejectReason.APERIODIC, drive.tone(440.0, 1, aperiodicity = ceiling + 1e-9).single().reject)
        }
    }

    @Test
    fun `POL-11 a tone under the RMS floor is silence`() {
        for (mode in ResponseMode.entries) {
            val floor = ResponseModeTable.of(mode).rmsFloor
            val drive = Drive(Detector(mode))
            assertEquals(RejectReason.SILENCE, drive.tone(440.0, 1, rms = floor * 0.99).single().reject)
            assertEquals(HopClass.VOTE, drive.tone(440.0, 1, rms = floor).single().hopClass)
        }
    }

    @Test
    fun `POL-12 mic gain moves the gate and the meter and never the cents`() {
        val floor = ResponseModeTable.of(ResponseMode.NORMAL).rmsFloor
        val quiet = floor * 0.8
        val hz = 442.0

        val ungained = Drive(Detector()).tone(hz, 1, rms = quiet, gain = 1.0).single()
        assertEquals(RejectReason.SILENCE, ungained.reject)
        assertEquals(quiet, ungained.level, 1e-12)

        val readings = listOf(2.0, 10.0, 100.0).map { gain ->
            val drive = Drive(Detector())
            val last = drive.tone(hz, 10, rms = quiet, gain = gain).last()
            assertEquals(quiet * gain, last.level, 1e-12)
            drive.saved.last()
        }
        val cents = readings.map { it.cents }.distinct()
        assertEquals("cents differ by gain: $cents", 1, cents.size)
        assertEquals(Pitch.centsFrom(hz, a4, Pitch.A4_DEFAULT_HZ), cents.single(), 1e-12)
    }

    @Test
    fun `POL-13 a period outside A0 to C8 is a reject`() {
        val drive = Drive(Detector())
        assertEquals(RejectReason.OUTSIDE_WINDOW, drive.note(20, 1).single().reject)
        assertEquals(RejectReason.OUTSIDE_WINDOW, drive.note(109, 1).single().reject)
        assertEquals(HopClass.VOTE, drive.note(21, 1).single().hopClass)
        assertEquals(HopClass.VOTE, drive.note(108, 1).single().hopClass)
        // Half a semitone below A0 still rounds to A0; a hair further is out.
        val belowA0 = Pitch.hzFromMidi(21, Pitch.A4_DEFAULT_HZ) * Math.pow(2.0, -0.49 / 12.0)
        val wellBelow = Pitch.hzFromMidi(21, Pitch.A4_DEFAULT_HZ) * Math.pow(2.0, -0.51 / 12.0)
        assertEquals(HopClass.VOTE, drive.tone(belowA0, 1).single().hopClass)
        assertEquals(RejectReason.OUTSIDE_WINDOW, drive.tone(wellBelow, 1).single().reject)
    }

    @Test
    fun `POL-14 leaps of any size are saved and no guard outlives silence`() {
        val leaps = mapOf("octave up" to 81, "fifth down" to 62, "sixth up" to 78)
        for ((name, target) in leaps) {
            val drive = Drive(Detector(ResponseMode.NORMAL))
            drive.note(a4, Hop.count(2.0))
            val leap = drive.hop
            drive.note(target, Hop.count(1.0))
            val saved = drive.saved.filter { it.hop >= leap && it.midiSounding == target }
            assertTrue("$name: no readings saved for $target", saved.isNotEmpty())
            // One second minus the confirm and two edges, give or take the hop count rounding.
            val expected = Hop.count(1.0) - drive.detector.params.firstReadingHops
            assertTrue("$name: ${saved.size} readings, expected about $expected", kotlin.math.abs(saved.size - expected) <= 1)
        }
    }

    @Test
    fun `ATK-02 one attack per attack edge, with its landing cents, then one settled reading`() {
        val hz = 442.0
        val drive = Drive(Detector(ResponseMode.NORMAL))
        drive.tone(hz, 40)
        val attack = drive.attacks.single()
        assertEquals(a4, attack.midiSounding)
        assertEquals(Pitch.centsFrom(hz, a4, Pitch.A4_DEFAULT_HZ), attack.landingCents, 1e-9)
        assertEquals(0, attack.a4Generation)

        val settled = drive.decisions.filter { it.firstSettled }
        assertEquals(1, settled.size)
        assertEquals(drive.decisions.first { it.saved != null }.hop, settled.single().hop)
        assertTrue(settled.single().hop > attack.hop)
        // Later hops of the held note change the readings only.
        assertTrue(drive.decisions.filter { it.hop > settled.single().hop }.none { it.attack != null || it.firstSettled })

        // A re-attack is a second attack with its own settled reading.
        drive.silence(10)
        drive.tone(hz, 40)
        assertEquals(2, drive.attacks.size)
        assertEquals(2, drive.decisions.count { it.firstSettled })
    }

    @Test
    fun `ATK-02 the landing is where the confirming hops put the note, before it settled`() {
        val drive = Drive(Detector(ResponseMode.NORMAL))
        // Scoops in from 20 cents flat over the confirming hops, then settles on pitch.
        val sharpness = listOf(-20.0, -12.0, -4.0)
        for (c in sharpness) drive.tone(Pitch.hzFromMidi(a4, Pitch.A4_DEFAULT_HZ) * Math.pow(2.0, c / 1200.0), 1)
        drive.note(a4, 20)
        val attack = drive.attacks.single()
        assertEquals(sharpness.average(), attack.landingCents, 1e-6)
        assertEquals(0.0, drive.saved.first().cents, 1e-9)
    }

    @Test
    fun `a rejected hop does not reset a held note until the window has forgotten it`() {
        val drive = Drive(Detector(ResponseMode.NORMAL))
        drive.note(a4, 30)
        drive.silence(1)
        assertEquals(a4, drive.detector.shownNote)
        drive.note(a4, 5)
        assertEquals(1, drive.attacks.size)
        assertFalse(drive.releases.isNotEmpty())
    }
}
