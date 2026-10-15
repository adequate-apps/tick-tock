// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine

/**
 * The native methods of [NativeBridge] as an interface, so the stream
 * backend can be proven on the JVM against a fake of the library. The
 * signatures are the bridge's; the documentation of each lives there.
 * [NativeBridge] is the one production implementation.
 */
interface NativeCalls {
    fun nativeAbiVersion(): Int
    fun nativeCreate(): Long
    fun nativeDestroy(handle: Long)

    fun nativeRegister(handle: Long, tool: Int)
    fun nativeUnregister(handle: Long, tool: Int)
    fun nativeVoiceOn(handle: Long, slot: Int, midi: Int, voice: Int, gain: Float): Boolean
    fun nativeVoiceOff(handle: Long, slot: Int): Boolean
    fun nativeVoiceGain(handle: Long, slot: Int, gain: Float): Boolean
    fun nativeSetA4(handle: Long, hz: Double): Boolean
    fun nativeDuck(handle: Long, gain: Float): Boolean
    fun nativeMetroStart(
        handle: Long, bpm: Int, beatsPerBar: Int, beatUnit: Int, compoundPulse: Boolean, accents: IntArray,
        subdivision: Int, click: Int, gain: Float, barsOn: Int, barsOff: Int, beatLimit: Int, generation: Int,
    ): Boolean
    fun nativeMetroUpdate(
        handle: Long, bpm: Int, beatsPerBar: Int, beatUnit: Int, compoundPulse: Boolean, accents: IntArray,
        subdivision: Int, click: Int, gain: Float, barsOn: Int, barsOff: Int, beatLimit: Int, generation: Int,
    ): Boolean
    fun nativeMetroStop(handle: Long): Boolean
    fun nativeMute(handle: Long, reason: Int, active: Boolean): Boolean

    fun nativePark(handle: Long, reason: Int)
    fun nativeResume(handle: Long)

    fun nativeDrainEvents(handle: Long, listener: NativeBridge.Listener, timeoutMillis: Int): Int
    fun nativeLatencyMillis(handle: Long): Int
    fun nativeDiagnostics(handle: Long): LongArray
}
