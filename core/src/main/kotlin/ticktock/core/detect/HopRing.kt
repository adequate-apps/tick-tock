// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.detect

/**
 * A bounded float ring between the capture side and the analysis side. A
 * block that does not fit pushes the oldest unread audio out, so the newest
 * audio is always what gets analysed and every frame that fell out is
 * counted (POL-16). Access is serialised with a plain monitor: neither side
 * is the render callback, and the analysis side reads whole hops at a time.
 */
class HopRing(val capacityFrames: Int) {
    init {
        require(capacityFrames > 0) { "ring capacity must be positive" }
    }

    private val buffer = FloatArray(capacityFrames)
    private var readPos = 0
    private var count = 0
    private var overflowFrames = 0L

    @Synchronized
    fun available(): Int = count

    @Synchronized
    fun write(samples: FloatArray, frames: Int) {
        require(frames in 0..samples.size) { "frames $frames outside the block of ${samples.size}" }
        var offset = 0
        var length = frames
        if (length > capacityFrames) {
            // The block alone is larger than the ring: only its tail can be kept.
            overflowFrames += length - capacityFrames
            offset = length - capacityFrames
            length = capacityFrames
        }
        val free = capacityFrames - count
        if (length > free) {
            val drop = length - free
            overflowFrames += drop
            readPos = (readPos + drop) % capacityFrames
            count -= drop
        }
        var writePos = (readPos + count) % capacityFrames
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, capacityFrames - writePos)
            System.arraycopy(samples, offset, buffer, writePos, chunk)
            offset += chunk
            remaining -= chunk
            writePos = (writePos + chunk) % capacityFrames
        }
        count += length
    }

    /** Copies [frames] into [destination] and consumes them; false when fewer are buffered. */
    @Synchronized
    fun read(destination: FloatArray, frames: Int): Boolean {
        if (frames > count) return false
        copyOut(destination, frames)
        return true
    }

    /**
     * As [read], and also reports the frames pushed out unread before these,
     * taken together with the copy so the reader learns of a gap with the
     * first audio after it and never a hop late. Null when fewer than [frames]
     * are buffered; the overflow count then waits for the next read.
     */
    @Synchronized
    fun readHop(destination: FloatArray, frames: Int): Long? {
        if (frames > count) return null
        val lost = overflowFrames
        overflowFrames = 0
        copyOut(destination, frames)
        return lost
    }

    private fun copyOut(destination: FloatArray, frames: Int) {
        var offset = 0
        var remaining = frames
        while (remaining > 0) {
            val chunk = minOf(remaining, capacityFrames - readPos)
            System.arraycopy(buffer, readPos, destination, offset, chunk)
            offset += chunk
            remaining -= chunk
            readPos = (readPos + chunk) % capacityFrames
        }
        count -= frames
    }

    /** Frames pushed out unread since the last call, and resets the count. */
    @Synchronized
    fun takeOverflow(): Long {
        val overflow = overflowFrames
        overflowFrames = 0
        return overflow
    }
}
