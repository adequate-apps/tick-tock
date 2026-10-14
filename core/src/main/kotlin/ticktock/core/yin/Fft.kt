// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.yin

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Forward complex FFT for any length whose only prime factors are 2, 3 and 5.
 * YIN needs a transform of length N + maxLag, which is never a power of two,
 * and padding up to one would cost up to a doubling of work per hop (DET-05).
 * Mixed-radix decimation in time keeps the transform at the 5-smooth length.
 *
 * The radix-2 stages, which are most of them, run a plain butterfly: one
 * complex multiply per pair. The radix-3 and radix-5 stages run the small
 * DFT directly, with the twiddle index stepped rather than reduced modulo
 * the length. Both matter for DET-17: the transform is most of the cost of
 * a hop.
 *
 * One instance owns its tables and scratch space, so a transform allocates
 * nothing; an instance is not safe to share between threads.
 */
class Fft(val length: Int) {
    private val factors: IntArray = factorise(length)
    private val cosTable = DoubleArray(length) { cos(2.0 * PI * it / length) }
    private val sinTable = DoubleArray(length) { -sin(2.0 * PI * it / length) }
    private val scratchRe = DoubleArray(length)
    private val scratchIm = DoubleArray(length)
    private val gatherRe = DoubleArray(MAX_RADIX)
    private val gatherIm = DoubleArray(MAX_RADIX)

    /** Replaces [re] and [im] with their forward transform; both must be [length] long. */
    fun forward(re: DoubleArray, im: DoubleArray) {
        require(re.size == length && im.size == length) { "arrays of ${re.size}/${im.size} for an FFT of $length" }
        System.arraycopy(re, 0, scratchRe, 0, length)
        System.arraycopy(im, 0, scratchIm, 0, length)
        transform(scratchRe, scratchIm, 0, 1, length, re, im, 0, 0)
    }

    /**
     * DFT of the [n] strided inputs at [offset], [offset] + [stride], ... into
     * [outRe]/[outIm] at [outOffset]. Decimation in time: the [factors] entry at
     * [depth] splits the input into that many interleaved sequences, each
     * transformed recursively, then combined with twiddles from the shared table.
     */
    private fun transform(
        inRe: DoubleArray,
        inIm: DoubleArray,
        offset: Int,
        stride: Int,
        n: Int,
        outRe: DoubleArray,
        outIm: DoubleArray,
        outOffset: Int,
        depth: Int,
    ) {
        if (n == 1) {
            outRe[outOffset] = inRe[offset]
            outIm[outOffset] = inIm[offset]
            return
        }
        val radix = factors[depth]
        val m = n / radix
        for (r in 0 until radix) {
            transform(inRe, inIm, offset + r * stride, stride * radix, m, outRe, outIm, outOffset + r * m, depth + 1)
        }
        // W_n^t is the full table's entry at t * (length / n).
        val tableStep = length / n
        if (radix == 2) {
            butterfly(outRe, outIm, outOffset, m, tableStep)
            return
        }
        for (k in 0 until m) {
            for (r in 0 until radix) {
                gatherRe[r] = outRe[outOffset + r * m + k]
                gatherIm[r] = outIm[outOffset + r * m + k]
            }
            for (q in 0 until radix) {
                val bin = k + q * m
                // The twiddle for term r is W_n^(r * bin); its table index
                // steps by bin * tableStep and wraps at the table's end,
                // which is the modulo without the division.
                val step = bin * tableStep
                var t = 0
                var sumRe = 0.0
                var sumIm = 0.0
                for (r in 0 until radix) {
                    val c = cosTable[t]
                    val s = sinTable[t]
                    sumRe += gatherRe[r] * c - gatherIm[r] * s
                    sumIm += gatherRe[r] * s + gatherIm[r] * c
                    t += step
                    if (t >= length) t -= length
                }
                outRe[outOffset + bin] = sumRe
                outIm[outOffset + bin] = sumIm
            }
        }
    }

    /** Combines two half-transforms of [m] bins each into one of 2[m]: out[k] = a + W b, out[k + m] = a - W b. */
    private fun butterfly(outRe: DoubleArray, outIm: DoubleArray, outOffset: Int, m: Int, tableStep: Int) {
        var t = 0
        for (k in 0 until m) {
            val c = cosTable[t]
            val s = sinTable[t]
            t += tableStep
            val a = outOffset + k
            val b = a + m
            val bRe = outRe[b]
            val bIm = outIm[b]
            val twiddledRe = bRe * c - bIm * s
            val twiddledIm = bRe * s + bIm * c
            val aRe = outRe[a]
            val aIm = outIm[a]
            outRe[a] = aRe + twiddledRe
            outIm[a] = aIm + twiddledIm
            outRe[b] = aRe - twiddledRe
            outIm[b] = aIm - twiddledIm
        }
    }

    companion object {
        private const val MAX_RADIX = 5
        private val RADICES = intArrayOf(2, 3, 5)

        /** True when [n] is positive and has no prime factor beyond 5. */
        fun isFiveSmooth(n: Int): Boolean {
            if (n <= 0) return false
            var rest = n
            for (p in RADICES) while (rest % p == 0) rest /= p
            return rest == 1
        }

        /** The smallest 5-smooth integer that is at least [n]. */
        fun smallestFiveSmoothAtLeast(n: Int): Int {
            require(n >= 1) { "no 5-smooth length below 1" }
            var candidate = n
            while (!isFiveSmooth(candidate)) candidate++
            return candidate
        }

        /** The smallest even 5-smooth integer that is at least [n], the length [RealFft] takes. */
        fun smallestEvenFiveSmoothAtLeast(n: Int): Int {
            var candidate = smallestFiveSmoothAtLeast(n)
            while (candidate % 2 != 0) candidate = smallestFiveSmoothAtLeast(candidate + 1)
            return candidate
        }

        private fun factorise(n: Int): IntArray {
            require(isFiveSmooth(n)) { "FFT length $n has a prime factor beyond 5" }
            val out = ArrayList<Int>()
            var rest = n
            for (p in RADICES) while (rest % p == 0) {
                out.add(p)
                rest /= p
            }
            return out.toIntArray()
        }
    }
}

/**
 * Forward FFT of a real sequence of even 5-smooth [length], giving the bins
 * 0 to [length] / 2; the rest mirror them. The even and odd samples ride as
 * the real and imaginary parts of one complex transform of half the length,
 * then one pass of twiddles separates the two interleaved spectra and joins
 * them. That halves the work of each of YIN's two transforms (DET-17), which
 * are both real: the window, and its power spectrum.
 *
 * One instance owns its tables and scratch space; not safe to share between threads.
 */
class RealFft(val length: Int) {
    init {
        require(length >= 2 && length % 2 == 0) { "a real FFT needs an even length, got $length" }
    }

    /** Bins a transform produces: 0 to [length] / 2 inclusive. */
    val bins: Int = length / 2 + 1

    private val half = length / 2
    private val complex = Fft(half)
    private val zRe = DoubleArray(half)
    private val zIm = DoubleArray(half)
    private val cosTable = DoubleArray(bins) { cos(2.0 * PI * it / length) }
    private val sinTable = DoubleArray(bins) { -sin(2.0 * PI * it / length) }

    /**
     * Transforms [x], [length] samples, into [outRe] and [outIm], which hold
     * [bins] values each afterwards. [x] is left as it was.
     */
    fun forward(x: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        require(x.size == length) { "${x.size} samples for a real FFT of $length" }
        require(outRe.size >= bins && outIm.size >= bins) { "the output needs $bins bins" }
        for (m in 0 until half) {
            zRe[m] = x[2 * m]
            zIm[m] = x[2 * m + 1]
        }
        complex.forward(zRe, zIm)
        // Z[k] = E[k] + i O[k] for the even and odd spectra, both of period
        // half; conjugate symmetry of a real sequence gives E and O back:
        //   E[k] = (Z[k] + conj Z[half - k]) / 2
        //   O[k] = (Z[k] - conj Z[half - k]) / 2i
        //   X[k] = E[k] + W_length^k O[k]
        for (k in 0 until bins) {
            val a = if (k == half) 0 else k
            val b = if (k == 0) 0 else half - k
            val zaRe = zRe[a]
            val zaIm = zIm[a]
            val zbRe = zRe[b]
            val zbIm = -zIm[b]
            val evenRe = (zaRe + zbRe) / 2.0
            val evenIm = (zaIm + zbIm) / 2.0
            val oddRe = (zaIm - zbIm) / 2.0
            val oddIm = (zbRe - zaRe) / 2.0
            val c = cosTable[k]
            val s = sinTable[k]
            outRe[k] = evenRe + oddRe * c - oddIm * s
            outIm[k] = evenIm + oddRe * s + oddIm * c
        }
    }
}
