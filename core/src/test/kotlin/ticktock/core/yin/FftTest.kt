// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.yin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** DET-05: the transform length is 5-smooth, never a power-of-two pad, and the transform is a DFT. */
class FftTest {
    @Test
    fun `DET-05 smallest 5-smooth length at or above n`() {
        assertEquals(6400, Fft.smallestFiveSmoothAtLeast(6342))
        assertEquals(6400, Fft.smallestFiveSmoothAtLeast(6400))
        assertEquals(5832, Fft.smallestFiveSmoothAtLeast(5827))
        assertEquals(4320, Fft.smallestFiveSmoothAtLeast(4097))
        assertEquals(1, Fft.smallestFiveSmoothAtLeast(1))
        assertEquals(8, Fft.smallestFiveSmoothAtLeast(7))
        assertEquals(12, Fft.smallestFiveSmoothAtLeast(11))
        assertTrue(Fft.isFiveSmooth(6400))
        assertTrue(Fft.isFiveSmooth(5832))
        assertFalse(Fft.isFiveSmooth(6342))
        assertFalse(Fft.isFiveSmooth(7))
        assertFalse(Fft.isFiveSmooth(0))
    }

    @Test
    fun `DET-05 the YIN transform is 6400 points at 48 kHz and no power of two`() {
        val yin48 = Yin(48000)
        assertEquals(6400, yin48.fftLength)
        assertEquals(Fft.smallestFiveSmoothAtLeast(yin48.windowFrames + yin48.maxLag + 1), yin48.fftLength)
        val yin44 = Yin(44100)
        assertEquals(Fft.smallestFiveSmoothAtLeast(yin44.windowFrames + yin44.maxLag + 1), yin44.fftLength)
        assertTrue(Fft.isFiveSmooth(yin44.fftLength))
        for (yin in listOf(yin44, yin48)) {
            assertTrue("no power-of-two pad at ${yin.sampleRate}", yin.fftLength and (yin.fftLength - 1) != 0)
            assertTrue(yin.fftLength >= yin.windowFrames + yin.maxLag + 1)
        }
    }

    @Test
    fun `DET-05 the mixed-radix transform matches a direct DFT`() {
        for (length in intArrayOf(1, 2, 3, 5, 6, 30, 60, 243, 250, 1024, 5832, 6400)) {
            val random = Random(length.toLong())
            val re = DoubleArray(length) { random.nextDouble() * 2 - 1 }
            val im = DoubleArray(length) { random.nextDouble() * 2 - 1 }
            val expectedRe = DoubleArray(length)
            val expectedIm = DoubleArray(length)
            for (k in 0 until length) {
                var sumRe = 0.0
                var sumIm = 0.0
                for (n in 0 until length) {
                    val angle = -2.0 * PI * ((k.toLong() * n) % length) / length
                    sumRe += re[n] * cos(angle) - im[n] * sin(angle)
                    sumIm += re[n] * sin(angle) + im[n] * cos(angle)
                }
                expectedRe[k] = sumRe
                expectedIm[k] = sumIm
            }
            Fft(length).forward(re, im)
            for (k in 0 until length) {
                assertEquals("re[$k] of $length", expectedRe[k], re[k], 1e-8 * length)
                assertEquals("im[$k] of $length", expectedIm[k], im[k], 1e-8 * length)
            }
        }
    }

    @Test
    fun `a length with a prime factor beyond 5 is refused`() {
        val failure = runCatching { Fft(7) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
