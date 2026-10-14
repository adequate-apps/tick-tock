// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine

/**
 * The Kotlin face of the one JNI file (engine/src/main/cpp/JniBridge.cpp).
 * One native method per [ticktock.core.contracts.OutputCommand] variant,
 * [nativePark] and [nativeResume] for the route and call policies, and
 * [nativeDrainEvents], which hands every [ticktock.core.contracts.OutputEvent]
 * to a [Listener] as numbers. Enum arguments are Kotlin ordinals.
 *
 * Loading the library is the caller's job ([load]), so JVM tests that touch
 * this class never need the native code.
 */
class NativeBridge {
    /**
     * How an event arrives. `kind` is the OutputEvent variant in declaration
     * order: 0 Opened, 1 Closed, 2 Beat, 3 Parked, 4 Resumed, 5 Failed,
     * 6 Counters. The fields per kind:
     * - Opened, Resumed: a sampleRate, b framesPerBlock, c deviceId, d latencyMillis,
     *   tools the resumed set as a bit mask of OutputTool ordinals (0 for Opened);
     * - Beat: a index, b beatInBar, c frame, d nanos (CLOCK_MONOTONIC);
     * - Parked: a ParkReason ordinal, tools the parked set;
     * - Failed: a deviceId, ints the refused sample rates;
     * - Counters: a lateBeatsDropped, b underruns.
     * The route class and device name come from the device id through the
     * audio manager on the control thread.
     */
    interface Listener {
        fun onEvent(kind: Int, a: Long, b: Long, c: Long, d: Long, tools: Int, ints: IntArray?)
    }

    external fun nativeAbiVersion(): Int
    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)

    external fun nativeRegister(handle: Long, tool: Int)
    external fun nativeUnregister(handle: Long, tool: Int)
    external fun nativeVoiceOn(handle: Long, slot: Int, midi: Int, voice: Int, gain: Float): Boolean
    external fun nativeVoiceOff(handle: Long, slot: Int): Boolean
    external fun nativeVoiceGain(handle: Long, slot: Int, gain: Float): Boolean
    external fun nativeSetA4(handle: Long, hz: Double): Boolean
    external fun nativeDuck(handle: Long, gain: Float): Boolean
    external fun nativeMetroStart(
        handle: Long, bpm: Int, beatsPerBar: Int, beatUnit: Int, compoundPulse: Boolean, accents: IntArray,
        subdivision: Int, click: Int, gain: Float, barsOn: Int, barsOff: Int,
    ): Boolean
    external fun nativeMetroUpdate(
        handle: Long, bpm: Int, beatsPerBar: Int, beatUnit: Int, compoundPulse: Boolean, accents: IntArray,
        subdivision: Int, click: Int, gain: Float, barsOn: Int, barsOff: Int,
    ): Boolean
    external fun nativeMetroStop(handle: Long): Boolean
    external fun nativeMute(handle: Long, reason: Int, active: Boolean): Boolean

    /** Closes the stream and keeps the registered set parked (ParkReason ordinal). */
    external fun nativePark(handle: Long, reason: Int)
    /** Reopens for the parked set at whatever rate the device now gives. */
    external fun nativeResume(handle: Long)

    /** Waits up to [timeoutMillis] for events, delivers them, returns how many. */
    external fun nativeDrainEvents(handle: Long, listener: Listener, timeoutMillis: Int): Int
    external fun nativeLatencyMillis(handle: Long): Int
    /**
     * [lateBeatsDropped, underruns, bufferBursts, growthSteps, droppedCommands,
     * droppedBeats, framesRendered, sampleRate, streamOpen, parked].
     */
    external fun nativeDiagnostics(handle: Long): LongArray

    companion object {
        /** Loads the library once and checks the ABI; throws on a mismatch. */
        fun load(): NativeBridge {
            System.loadLibrary(EngineLibrary.NAME)
            val bridge = NativeBridge()
            val native = bridge.nativeAbiVersion()
            check(native == EngineLibrary.ABI_VERSION) {
                "native engine ABI $native, Kotlin expects ${EngineLibrary.ABI_VERSION}"
            }
            return bridge
        }
    }
}
