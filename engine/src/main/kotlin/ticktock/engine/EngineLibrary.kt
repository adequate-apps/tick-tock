// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine

/**
 * Names the native library so the JNI bridge (M2) and the host test build agree
 * on it. Loading happens in the bridge, never here, so JVM tests that touch
 * this module stay free of native code.
 */
object EngineLibrary {
    const val NAME = "ticktock_engine"

    /** Must equal the value compiled into engine.cpp; checked when the bridge loads. */
    const val ABI_VERSION = 2
}
