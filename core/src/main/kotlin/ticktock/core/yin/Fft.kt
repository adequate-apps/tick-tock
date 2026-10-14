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
        for (k in 0 until m) {
            for (r in 0 until radix) {
                gatherRe[r] = outRe[outOffset + r * m + k]
                gatherIm[r] = outIm[outOffset + r * m + k]
            }
            for (q in 0 until radix) {
                val bin = k + q * m
                var sumRe = 0.0
                var sumIm = 0.0
                for (r in 0 until radix) {
                    val t = ((r * bin) % n) * tableStep
                    val c = cosTable[t]
                    val s = sinTable[t]
                    sumRe += gatherRe[r] * c - gatherIm[r] * s
                    sumIm += gatherRe[r] * s + gatherIm[r] * c
                }
                outRe[outOffset + bin] = sumRe
                outIm[outOffset + bin] = sumIm
            }
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
