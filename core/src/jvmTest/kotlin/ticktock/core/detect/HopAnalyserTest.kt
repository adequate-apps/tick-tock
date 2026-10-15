// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.AudioInput
import ticktock.core.contracts.AudioSink
import ticktock.core.contracts.InputInfo
import ticktock.core.contracts.InputSource
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.contracts.fakes.FakeClock
import ticktock.core.detect.fakes.Sine
import ticktock.core.detect.fakes.Synth
import ticktock.core.detect.fakes.ZeroCrossingEstimator
import ticktock.core.music.Cents
import ticktock.core.music.Pitch
import kotlin.random.Random

/**
 * Spec 2.4 on frames: the fake input pushes PCM, the analyser cuts hops, a
 * synthetic estimator stands in for YIN, and the detector decides.
 */
class HopAnalyserTest {
    private val rate = 48_000
    private val hopFrames = Hop.frames(rate)

    private fun analyser(ringHops: Int = HopAnalyser.DEFAULT_RING_HOPS, estimator: PitchEstimator = ZeroCrossingEstimator()) =
        HopAnalyser(Detector(ResponseMode.NORMAL), estimator, ringHops)

    @Test
    fun `POL-28 a restored gain must present before fresh audio establishes the probe baseline`() {
        val detector = Detector(ResponseMode.FAST,
            sounding = ticktock.core.contracts.fakes.FakeSoundingNotes(69))
        val analyser = HopAnalyser(detector, ZeroCrossingEstimator())
        val input = FakeAudioInput(sampleRate = rate)
        analyser.attach(input).getOrThrow()
        val restored = analyser.deferBleedEvidence()
        val oldLeak = Sine(440.0, 0.06, rate)
        input.push(oldLeak.next(16 * hopFrames))
        val queued = analyser.drain()
        assertTrue("a ducked old stream supplies no fresh baseline", queued.all { it.duck == null })
        assertTrue(queued.all { it.saved == null && it.display == null })
        restored()
        val fullLeak = Sine(440.0, 0.2, rate)
        input.push(fullLeak.next(4 * hopFrames))
        var probe: (() -> Unit)? = null
        val fresh = analyser.drain { if (it.duck == BleedPolicy.DUCK_FLOOR) probe = analyser.deferBleedEvidence() }
        assertEquals(listOf(BleedPolicy.DUCK_FLOOR), fresh.mapNotNull { it.duck })
        assertTrue(detector.bleedState.preDuckLevel > 0.13)
        probe!!.invoke()
        input.push(oldLeak.next(16 * hopFrames))
        assertTrue(analyser.drain().all { it.saved == null && it.display == null })
        analyser.detach()
    }

    @Test
    fun `POL-28 queued and upstream buffered audio cannot prove unison before a settled duck`() {
        val detector = Detector(ResponseMode.FAST,
            sounding = ticktock.core.contracts.fakes.FakeSoundingNotes(69))
        val analyser = HopAnalyser(detector, ZeroCrossingEstimator())
        val input = FakeAudioInput(sampleRate = rate, bufferFrames = hopFrames * 16)
        analyser.attach(input).getOrThrow()
        val sine = Sine(440.0, 0.2, rate)
        var settled: (() -> Unit)? = null
        val decide: (Decision) -> Unit = { if (it.duck == BleedPolicy.DUCK_FLOOR) settled = analyser.deferBleedEvidence() }
        input.push(sine.next(16 * hopFrames))
        val queued = analyser.drain(decide)
        assertNotNull(settled)
        assertTrue(queued.all { it.saved == null && it.display == null })
        // A delayed output acknowledgement leaves every subsequent batch excluded.
        repeat(3) {
            input.push(sine.next(16 * hopFrames))
            assertTrue(analyser.drain(decide).all { it.saved == null && it.display == null })
        }
        // Even after acknowledgement, sixteen hops can still be inside AudioRecord.
        settled!!.invoke()
        input.push(sine.next(16 * hopFrames))
        assertTrue(analyser.drain(decide).all { it.saved == null && it.display == null })
        val leaked = Sine(440.0, 0.06, rate)
        repeat(2) {
            input.push(leaked.next(16 * hopFrames))
            assertTrue(analyser.drain(decide).all { it.saved == null && it.display == null })
        }
        assertNull(detector.shownNote)
        // Fresh unison at the player's original level can still earn admission.
        input.push(sine.next(16 * hopFrames))
        assertTrue(analyser.drain(decide).any { it.saved?.midiSounding == 69 })
        analyser.detach()
    }

    @Test
    fun `POL-28 a stale duck acknowledgement cannot release a newer fence or capture`() {
        val detector = Detector(ResponseMode.FAST,
            sounding = ticktock.core.contracts.fakes.FakeSoundingNotes(69))
        val analyser = HopAnalyser(detector, ZeroCrossingEstimator())
        val input = FakeAudioInput(sampleRate = rate)
        analyser.attach(input).getOrThrow()
        val old = analyser.deferBleedEvidence()
        val current = analyser.deferBleedEvidence()
        old()
        val sine = Sine(440.0, 0.2, rate)
        input.push(sine.next(16 * hopFrames))
        assertTrue(analyser.drain().all { it.saved == null && it.display == null })
        analyser.detach()
        analyser.attach(input).getOrThrow()
        analyser.deferBleedEvidence()
        current()
        input.push(sine.next(16 * hopFrames))
        assertTrue(analyser.drain().all { it.saved == null && it.display == null })
        analyser.detach()
    }

    @Test
    fun `the synthetic estimator reads a clean sine to a fraction of a cent and rejects noise`() {
        val estimator = ZeroCrossingEstimator()
        val sine = Sine(440.0, 0.1, rate)
        val window = sine.next(Hop.windowFrames(rate))
        val estimate = estimator.estimate(window, window.size, rate)
        assertEquals(440.0, estimate.hz, 0.05)
        assertTrue("aperiodicity ${estimate.aperiodicity}", estimate.aperiodicity < 0.01)
        assertEquals(0.1 / Math.sqrt(2.0), estimate.rms, 1e-3)

        val noise = Synth.noise(Hop.windowFrames(rate), rms = 0.1)
        val noisy = estimator.estimate(noise, noise.size, rate)
        assertTrue("noise aperiodicity ${noisy.aperiodicity}", noisy.aperiodicity > ResponseModeTable.of(ResponseMode.FAST).aperiodicityCeiling)
        assertEquals(0.1, noisy.rms, 0.01)

        val silence = estimator.estimate(Synth.silence(Hop.windowFrames(rate)), Hop.windowFrames(rate), rate)
        assertFalse(silence.hasCandidate)
    }

    @Test
    fun `DET-17 a window under the gate's floor never reaches the estimator, and is the same silence reject with its level`() {
        var calls = 0
        val counting = PitchEstimator { window, frames, sampleRate ->
            calls++
            ZeroCrossingEstimator().estimate(window, frames, sampleRate)
        }
        val analyser = HopAnalyser(Detector(ResponseMode.NORMAL), counting)
        val input = FakeAudioInput(sampleRate = rate)
        analyser.attach(input).getOrThrow()

        // Three hops of silence, then three of a tone far under any floor.
        input.push(Synth.silence(hopFrames * 3))
        val faint = Sine(440.0, 1e-5, rate)
        input.push(faint.next(hopFrames * 3))
        val quiet = analyser.drain()
        assertEquals(0, calls)
        assertEquals(6, quiet.size)
        for (decision in quiet) {
            assertEquals(HopClass.REJECT, decision.hopClass)
            assertEquals(RejectReason.SILENCE, decision.reject)
        }
        assertEquals(1e-5 / Math.sqrt(2.0), quiet.last().level, 1e-7)

        // A tone over the floor reaches the estimator; the window that
        // straddles the faint hop and the first loud one is over it too.
        input.push(Sine(440.0, 0.1, rate).next(hopFrames * 3))
        val loud = analyser.drain()
        assertEquals(3, loud.size)
        assertEquals(3, calls)
        assertTrue(loud.all { it.hopClass == HopClass.VOTE })

        // The gain the gate sees is the analyser's: enough of it lifts the faint tone over the floor.
        analyser.micGain = 1_000.0
        input.push(faint.next(hopFrames * 2))
        analyser.drain()
        assertEquals(5, calls)
    }

    @Test
    fun `POL-15 a block of k hops is exactly k windows and no hop is analysed twice`() {
        val analyser = analyser()
        val input = FakeAudioInput(sampleRate = rate)
        analyser.attach(input).getOrThrow()
        val sine = Sine(440.0, 0.1, rate)

        input.push(sine.next(hopFrames * 4))
        val first = analyser.drain()
        assertEquals(listOf(0L, 1L, 2L, 3L), first.map { it.hop })
        assertTrue(analyser.drain().isEmpty())

        // A partial hop waits for the rest of itself.
        input.push(sine.next(hopFrames / 2))
        assertTrue(analyser.drain().isEmpty())
        input.push(sine.next(hopFrames - hopFrames / 2 + hopFrames))
        assertEquals(listOf(4L, 5L), analyser.drain().map { it.hop })
        assertEquals(6, analyser.counters.hopsAnalysed)
        assertEquals(0, analyser.counters.droppedHops)
    }

    @Test
    fun `POL-16 a block larger than the ring keeps the newest audio, counts the rest and goes on`() {
        val analyser = analyser(ringHops = 4)
        val input = FakeAudioInput(sampleRate = rate)
        analyser.attach(input).getOrThrow()
        val sine = Sine(440.0, 0.1, rate)

        // Six hops of silence then four of tone: only the tone fits. The first
        // kept hop has no neighbour before it and serves as context alone, so
        // three windows come out, numbered from where the tone sits in time.
        val block = Synth.silence(hopFrames * 6) + sine.next(hopFrames * 4)
        input.push(block)
        val decisions = analyser.drain()
        assertEquals(listOf(7L, 8L, 9L), decisions.map { it.hop })
        assertTrue("silence got through: ${decisions.map { it.reject }}", decisions.none { it.reject == RejectReason.SILENCE })
        assertEquals(7, analyser.counters.droppedHops)

        // The stream continues after the overflow.
        input.push(sine.next(hopFrames * 2))
        val more = analyser.drain()
        assertEquals(listOf(10L, 11L), more.map { it.hop })
        assertEquals(7, analyser.counters.droppedHops)
        assertEquals(5, analyser.counters.hopsAnalysed)
    }

    /**
     * POL-15 and POL-16 after an overflow. The ring dropped audio between the
     * last hop analysed and the next, so the two are no longer neighbours and
     * the window they would form never existed. The first hop after the gap
     * is context only, and hop numbering moves on by the hops lost, so a hop
     * index stays a time (POL-09). Each sample carries its own frame index,
     * which makes a window's continuity checkable to the sample.
     */
    @Test
    fun `POL-16 after an overflow no spliced window is analysed and hop numbering follows sounding time`() {
        for (dropped in intArrayOf(300, 1000, hopFrames)) {
            val label = "$dropped frames dropped"
            var frame = 0
            fun frames(n: Int) = FloatArray(n) { (frame++).toFloat() }
            val windows = ArrayList<FloatArray>()
            val recording = PitchEstimator { window, frames, _ ->
                windows += window.copyOf(frames)
                HopEstimate.none(0.0)
            }
            val analyser = HopAnalyser(Detector(ResponseMode.NORMAL), recording, ringHops = 16)
            val input = FakeAudioInput(sampleRate = rate)
            analyser.attach(input).getOrThrow()
            input.push(frames(hopFrames * 12))
            assertEquals(label, (0L until 12L).toList(), analyser.drain().map { it.hop })

            // One block beyond what is free: the ring keeps the newest 16 hops.
            input.push(frames(hopFrames * 16 + dropped))
            val after = analyser.drain()

            // Every window after the first (whose first half is the silence before the stream) is contiguous audio.
            for ((i, w) in windows.withIndex().drop(1)) {
                for (j in 1 until w.size) assertEquals("$label: window $i breaks at sample $j", w[j - 1] + 1f, w[j], 0f)
            }
            val lostHops = dropped / hopFrames
            assertEquals("$label: windows after the gap", 15, after.size)
            assertEquals("$label: first hop after the gap", 12L + lostHops + 1, after.first().hop)
            assertEquals("$label: hops lost plus the one spent as context", lostHops + 1L, analyser.counters.droppedHops)
            assertEquals(label, 12 + 15, analyser.counters.hopsAnalysed)
            // A hop index is sounding time to within a hop: the window's second half starts where the index says.
            for ((i, w) in windows.drop(12).withIndex()) {
                val start = after[i].hop * hopFrames
                assertTrue("$label: hop ${after[i].hop} starts at frame ${w[hopFrames]}", kotlin.math.abs(w[hopFrames] - start) < hopFrames)
            }
        }
    }

    @Test
    fun `POL-16 the ring itself drops the oldest unread frames and never corrupts the order`() {
        val ring = HopRing(8)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 6)
        ring.write(floatArrayOf(7f, 8f, 9f, 10f), 4)
        assertEquals(2, ring.takeOverflow())
        assertEquals(8, ring.available())
        val out = FloatArray(8)
        assertTrue(ring.read(out, 8))
        assertEquals(listOf(3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f), out.toList())
        assertFalse(ring.read(out, 1))

        // A block larger than the ring keeps only its tail.
        ring.write(FloatArray(20) { it.toFloat() }, 20)
        assertEquals(12, ring.takeOverflow())
        assertTrue(ring.read(out, 8))
        assertEquals((12..19).map { it.toFloat() }, out.toList())
        assertEquals(0, ring.takeOverflow())
    }

    @Test
    fun `POL-17 a restart replaces the stream and starts the counters over`() {
        val analyser = analyser()
        val input = FakeAudioInput(sampleRate = rate)
        analyser.attach(input).getOrThrow()
        val sine = Sine(440.0, 0.1, rate)
        input.push(sine.next(hopFrames * 3))
        assertEquals(3, analyser.drain().size)
        assertEquals(3, analyser.counters.hopsAnalysed)
        assertEquals(69, analyser.detector.shownNote)

        analyser.attach(input).getOrThrow()
        assertEquals(1, input.openCount)
        assertEquals(2, input.startCalls)
        assertEquals(0, analyser.counters.hopsAnalysed)
        assertNull(analyser.detector.shownNote)
        input.push(sine.next(hopFrames * 2))
        assertEquals(listOf(0L, 1L), analyser.drain().map { it.hop })

        // Moving to another input stops the first, so exactly one is open.
        val other = FakeAudioInput(sampleRate = 44_100, deviceName = "USB interface")
        analyser.attach(other).getOrThrow()
        assertEquals(0, input.openCount)
        assertEquals(1, other.openCount)
        assertEquals(2048, analyser.hopFrames)
        assertFalse(input.push(sine.next(hopFrames)))
    }

    @Test
    fun `POL-17 a sink from a replaced stream delivers nowhere`() {
        val analyser = analyser()
        val first = FakeAudioInput(sampleRate = rate)
        analyser.attach(first).getOrThrow()
        val second = FakeAudioInput(sampleRate = rate)
        analyser.attach(second).getOrThrow()
        // The first input was stopped, but a capture thread may still be mid-delivery.
        // Hand its old sink a block directly and prove nothing lands.
        assertFalse(first.push(Sine(440.0, 0.1, rate).next(hopFrames * 2)))
        assertTrue(analyser.drain().isEmpty())
        assertEquals(0, analyser.counters.droppedHops)
    }

    @Test
    fun `POL-18 dropped hops and analysis errors are counted since the stream started`() {
        var calls = 0
        val throwing = PitchEstimator { window, frames, sampleRate ->
            calls++
            if (calls == 3) throw IllegalStateException("estimator failed on call 3")
            ZeroCrossingEstimator().estimate(window, frames, sampleRate)
        }
        val analyser = analyser(ringHops = 4, estimator = throwing)
        val input = FakeAudioInput(sampleRate = rate)
        analyser.attach(input).getOrThrow()
        val sine = Sine(440.0, 0.1, rate)

        // Five hops into a four-hop ring: one is lost, the first kept is context
        // only, and the estimator throws on the third window it sees (hop 4).
        input.push(sine.next(hopFrames * 5))
        val decisions = analyser.drain()
        assertEquals(listOf(2L, 3L), decisions.map { it.hop })
        val counters = analyser.counters
        assertEquals(2, counters.droppedHops)
        assertEquals(1, counters.analysisErrors)
        assertEquals(2, counters.hopsAnalysed)
        assertNotNull(counters.lastAnalysisError)
        assertTrue(counters.lastAnalysisError!!.contains("call 3"))

        analyser.attach(input).getOrThrow()
        assertEquals(AnalysisCounters(), analyser.counters)
    }

    @Test
    fun `POL-09 bursty delivery with wall-clock jitter rejects no hop as transient`() {
        val analyser = analyser()
        val input = FakeAudioInput(sampleRate = rate)
        analyser.attach(input).getOrThrow()
        val clock = FakeClock(rate)
        val random = Random(3)
        val sine = Sine(440.0, 0.1, rate)
        val decisions = ArrayList<Decision>()

        val bursts = 50
        repeat(bursts) {
            // Eight hops at once, then anything from 0 to 200 ms of nothing.
            input.push(sine.next(hopFrames * 8))
            clock.advanceNanos(random.nextLong(0, 200_000_000))
            decisions += analyser.drain()
        }
        assertEquals(400, decisions.size)
        val rejected = decisions.filter { it.hopClass != HopClass.VOTE }
        assertEquals("rejected hops: ${rejected.map { it.hop to it.reject }}", 0, rejected.size)
        val params = analyser.detector.params
        assertEquals(400 - params.firstReadingHops, decisions.count { it.saved != null })
        assertEquals(0, analyser.counters.droppedHops)
    }

    @Test
    fun `POL-10 white noise at -20 dBFS through the frame path saves nothing and shows no note`() {
        val analyser = analyser()
        val input = FakeAudioInput(sampleRate = rate)
        analyser.attach(input).getOrThrow()
        val hops = Hop.count(10.0)
        val decisions = ArrayList<Decision>()
        for (i in 0 until hops) {
            input.push(Synth.noise(hopFrames, rms = 0.1, seed = i))
            decisions += analyser.drain()
        }
        assertEquals(hops, decisions.size)
        assertTrue(decisions.none { it.saved != null })
        assertTrue(decisions.all { it.incumbent == null })
    }

    @Test
    fun `POL-12 on frames the window reaches the estimator unscaled`() {
        val floor = ResponseModeTable.of(ResponseMode.NORMAL).rmsFloor
        // A sine whose RMS sits just under the floor: amplitude is RMS times root two.
        val amplitude = floor * 0.8 * Math.sqrt(2.0)

        fun run(gain: Double): Pair<List<Decision>, Reading?> {
            val analyser = analyser()
            analyser.micGain = gain
            val input = FakeAudioInput(sampleRate = rate)
            analyser.attach(input).getOrThrow()
            val sine = Sine(442.0, amplitude, rate)
            input.push(sine.next(hopFrames * 20))
            val decisions = analyser.drain()
            return decisions to decisions.lastOrNull { it.saved != null }?.saved
        }

        val (quiet, nothing) = run(1.0)
        assertTrue(quiet.all { it.reject == RejectReason.SILENCE })
        assertNull(nothing)
        val (_, atTwo) = run(2.0)
        val (_, atTen) = run(10.0)
        assertNotNull(atTwo)
        assertEquals(atTwo!!.cents, atTen!!.cents, 0.0)
        assertEquals(atTwo.hz, atTen.hz, 0.0)
        assertEquals(Pitch.centsFrom(442.0, 69, Pitch.A4_DEFAULT_HZ), atTwo.cents, 0.2)
    }

    @Test
    fun `A4W-01 a 415 Hz tone against A4 415 shows zero cents`() {
        val analyser = HopAnalyser(Detector(ResponseMode.NORMAL, a4 = A4Generation(415.0)), ZeroCrossingEstimator())
        val input = FakeAudioInput(sampleRate = rate)
        analyser.attach(input).getOrThrow()
        input.push(Sine(415.0, 0.1, rate).next(hopFrames * 20))
        val reading = analyser.drain().last { it.saved != null }.saved!!
        assertEquals(69, reading.midiSounding)
        assertEquals(0, Cents.shown(reading.cents, reading.hz))
        assertEquals(415.0, reading.a4Hz, 0.0)
    }

    /** An input that opens with [source], as the capture chain reports after falling back through the presets. */
    private fun inputOpening(source: InputSource) = object : AudioInput {
        private var sink: AudioSink? = null
        override fun start(sink: AudioSink): Result<InputInfo> {
            this.sink = sink
            return Result.success(InputInfo(rate, "Built-in microphone", source))
        }
        override fun stop() {
            sink = null
        }
        fun push(samples: FloatArray) = sink!!(samples, samples.size)
    }

    /**
     * SRC-03 on the path production takes: the preset is known only once the
     * input has opened, and the gate must shift to it. A note twice the
     * UNPROCESSED floor votes there; VOICE_RECOGNITION delivers the same
     * sound 18 dB hotter, so the same samples are silence under its floor and
     * pass once delivered as that preset would.
     */
    @Test
    fun `SRC-03 the preset the input opened with reaches the gate through the analyser`() {
        val floor = ResponseModeTable.of(ResponseMode.NORMAL).rmsFloor
        val amplitude = floor * 2.0 * Math.sqrt(2.0)
        fun run(source: InputSource, gainDb: Double = 0.0): List<Decision> {
            val analyser = analyser()
            val input = inputOpening(source)
            analyser.attach(input).getOrThrow()
            assertEquals(source, analyser.detector.source)
            input.push(Sine(440.0, amplitude * Math.pow(10.0, gainDb / 20.0), rate).next(hopFrames * 10))
            return analyser.drain()
        }
        assertTrue(run(InputSource.UNPROCESSED).all { it.hopClass == HopClass.VOTE })
        assertTrue(run(InputSource.VOICE_RECOGNITION).all { it.reject == RejectReason.SILENCE })
        assertTrue(run(InputSource.VOICE_RECOGNITION, gainDb = SourceLevel.offsetDb(InputSource.VOICE_RECOGNITION)).all { it.hopClass == HopClass.VOTE })
    }

    @Test
    fun `audio delivered before the stream is configured is counted, never lost in silence`() {
        // An input that delivers inside start(), before it has reported its rate.
        val eager = object : ticktock.core.contracts.AudioInput {
            override fun start(sink: ticktock.core.contracts.AudioSink): Result<ticktock.core.contracts.InputInfo> {
                sink(FloatArray(hopFrames * 2), hopFrames * 2)
                return Result.success(ticktock.core.contracts.InputInfo(rate, "Eager", ticktock.core.contracts.InputSource.FAKE))
            }
            override fun stop() = Unit
        }
        val analyser = analyser()
        analyser.attach(eager).getOrThrow()
        assertEquals(2, analyser.counters.droppedHops)
        assertTrue(analyser.drain().isEmpty())
    }
}
