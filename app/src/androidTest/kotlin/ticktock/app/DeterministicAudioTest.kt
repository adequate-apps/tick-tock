// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ticktock.core.contracts.fakes.FakeAudioInput
import ticktock.core.detect.A4Generation
import ticktock.core.detect.Decision
import ticktock.core.detect.Detector
import ticktock.core.detect.Hop
import ticktock.core.detect.HopAnalyser
import ticktock.core.detect.YinEstimator
import ticktock.engine.input.AnalysisThread

/** Device-side PCM enters the same ring, analysis worker and estimator as microphone capture. */
@RunWith(AndroidJUnit4::class)
class DeterministicAudioTest {
    @Test
    fun pitchMatrix() {
        val failures = mutableListOf<String>()
        val rows = mutableListOf("rate,a4,midi,timbre,seed,pcm_sha256,readings,worst_cents")
        // The reference sweep plus both A4 endpoints and the alternate concert reference.
        for (rate in RATES) for (a4 in listOf(440.0, 415.0, 442.0, 466.0)) {
            val midis = if (a4 == 440.0) 21..108 else listOf(21, 33, 45, 57, 69, 81, 93, 108)
            for (midi in midis) for ((timbre, partials) in TIMBRES.withIndex()) {
                val label = "$rate/$a4/$midi/$timbre"
                val hz = a4 * 2.0.pow((midi - 69) / 12.0)
                val seed = 1000L + midi
                val pcm = harmonic(hz, rate, Hop.frames(rate) * CASE_HOPS, partials, seed)
                val saved = mutableListOf<ticktock.core.detect.Reading>()
                AudioRig(rate, a4).use { rig ->
                    repeat(CASE_HOPS) { index ->
                        val batch = rig.feed(pcm.copyOfRange(index * rig.frames, (index + 1) * rig.frames))
                        batch.decision.saved?.let(saved::add)
                    }
                    rig.assertHealthy(CASE_HOPS)
                }
                val tolerance = if (timbre == 0) 0.6 else 1.0
                val worst = saved.maxOfOrNull { abs(1200.0 * ln(it.hz / hz) / ln(2.0)) } ?: Double.POSITIVE_INFINITY
                if (saved.size < 8 || saved.any { it.midiSounding != midi || it.a4Hz != a4 } || worst > tolerance) {
                    failures += "$label: ${saved.size} saved, worst=$worst cents, notes=${saved.map { it.midiSounding }.toSet()}"
                    if (InstrumentationRegistry.getArguments().getString("failFast") == "true") {
                        evidence("pitch-failures.txt", failures.joinToString("\n"))
                        throw AssertionError(failures.last())
                    }
                }
                rows += "$rate,$a4,$midi,$timbre,$seed,${sha256(pcm)},${saved.size},$worst"
            }
        }
        evidence("pitch-matrix.csv", rows.joinToString("\n") + "\n")
        evidence("pitch-failures.txt", failures.joinToString("\n"))
        assertEquals("matrix must run every case", 672, rows.size - 1)
        assertTrue(failures.take(30).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun perHopBudget() {
        val rows = mutableListOf("stimulus,index,nanos")
        val summaries = mutableListOf("stimulus,count,median_ms,p99_ms,max_ms")
        val failures = mutableListOf<String>()
        for (stimulus in 0..2) {
            val frames = Hop.frames(48_000)
            val total = WARMUP_HOPS + MEASURED_HOPS
            val pcm = if (stimulus == 2) {
                val random = Random(991L)
                FloatArray(frames * total) { ((random.nextDouble() * 2 - 1) * 0.1).toFloat() }
            } else harmonic(440.0, 48_000, frames * total, TIMBRES[stimulus], 991L)
            val timings = ArrayList<Long>()
            AudioRig(48_000, 440.0).use { rig ->
                repeat(total) { index ->
                    val sample = rig.feed(pcm.copyOfRange(index * frames, (index + 1) * frames))
                    if (index >= WARMUP_HOPS) {
                        assertTrue("a successful hop must have a positive cost", sample.nanos > 0)
                        timings += sample.nanos
                        rows += "$stimulus,${index - WARMUP_HOPS},${sample.nanos}"
                    }
                }
                rig.assertHealthy(total)
            }
            val ordered = timings.sorted()
            val median = ordered[MEASURED_HOPS / 2] / 1e6
            val p99 = ordered[(MEASURED_HOPS * 99 + 99) / 100 - 1] / 1e6
            val maximum = ordered.last() / 1e6
            summaries += "$stimulus,${ordered.size},$median,$p99,$maximum"
            if (median > 5.0 || p99 > 15.0) failures += "$stimulus: median=$median ms, p99=$p99 ms"
        }
        evidence("hop-timings.csv", rows.joinToString("\n") + "\n")
        evidence("hop-budget.csv", summaries.joinToString("\n") + "\n")
        assertEquals(3 * MEASURED_HOPS, rows.size - 1)
        assertTrue("emulator budget: " + failures.joinToString("; "), failures.isEmpty())
    }

    private data class Sample(val decision: Decision, val nanos: Long)

    private class AudioRig(rate: Int, a4: Double) : AutoCloseable {
        val frames = Hop.frames(rate)
        private val detector = Detector(a4 = A4Generation(a4))
        private val input = FakeAudioInput(rate)
        private val results = LinkedBlockingQueue<Sample>()
        private lateinit var worker: AnalysisThread
        private var next = 0L

        init {
            worker = AnalysisThread(
                HopAnalyser(detector, YinEstimator(detector)),
                promote = { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) },
                onDecisions = { decisions ->
                    for (decision in decisions) results.add(Sample(decision, worker.lastHopNanos))
                },
            )
            worker.start(input).getOrThrow()
        }

        fun feed(pcm: FloatArray): Sample {
            assertEquals(frames, pcm.size)
            assertTrue("no unconsumed prior hop", results.isEmpty())
            assertTrue(input.push(pcm))
            val result = results.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("analysis hop $next timed out")
            assertEquals("exactly one ordered hop per batch", next++, result.decision.hop)
            return result
        }

        fun assertHealthy(hops: Int) {
            assertEquals(hops.toLong(), worker.counters.hopsAnalysed)
            assertEquals(0L, worker.counters.analysisErrors)
            assertEquals(0L, worker.counters.droppedHops)
        }

        override fun close() = worker.stop()
    }

    companion object {
        private val RATES = listOf(44_100, 48_000)
        private const val CASE_HOPS = 20
        private const val WARMUP_HOPS = 100
        private const val MEASURED_HOPS = 1000
        private val TIMBRES = listOf(
            doubleArrayOf(1.0),
            doubleArrayOf(1.0, 0.9, 0.7, 0.6, 0.5, 0.4, 0.3, 0.25, 0.2, 0.15),
            doubleArrayOf(0.1, 1.0, 0.5, 0.3, 0.2),
        )

        private fun harmonic(hz: Double, rate: Int, frames: Int, partials: DoubleArray, seed: Long): FloatArray {
            val random = Random(seed)
            val values = DoubleArray(frames)
            for ((index, amplitude) in partials.withIndex()) {
                val phase = random.nextDouble() * 2 * PI
                val frequency = hz * (index + 1)
                if (frequency >= rate / 2.0) continue
                for (i in values.indices) values[i] += amplitude * sin(2 * PI * frequency * i / rate + phase)
            }
            val peak = values.maxOf { abs(it) }
            check(peak > 0)
            return FloatArray(frames) { (values[it] * 0.5 / peak).toFloat() }
        }

        private fun sha256(pcm: FloatArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (sample in pcm) {
                val bits = sample.toRawBits()
                repeat(4) { digest.update((bits ushr (it * 8)).toByte()) }
            }
            return digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }
        }

        private fun evidence(name: String, text: String) {
            val dir = AudioEvidence.directory()
            File(dir, name).writeText(text)
            File(dir, "device.txt").writeText("api=${Build.VERSION.SDK_INT}\nabi=${Build.SUPPORTED_ABIS.joinToString()}\n" +
                "fingerprint=${Build.FINGERPRINT}\nsource=deterministic-pcm\n")
        }
    }
}
