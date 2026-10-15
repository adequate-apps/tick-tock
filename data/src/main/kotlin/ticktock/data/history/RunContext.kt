// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.history

/** How a run was played (CTX-01); the stored value is [sql]. */
enum class RunKind(val sql: String) {
    FREE("free"),
    DRONE("drone"),
    EXERCISE("exercise"),
    ;

    companion object {
        fun of(sql: String): RunKind = entries.firstOrNull { it.sql == sql } ?: FREE
    }
}

/**
 * Everything about the player's setup that labels a run. A change to any field
 * opens a new run (SES-02, CTX-02), as does an A4 change, which arrives with
 * the readings themselves. The app sets it from prefs before the first reading
 * can arrive, so the first run carries the saved values (SES-01).
 */
data class RunContext(
    val instrumentKey: String,
    val transposition: Int,
    val nickname: String = "",
    val kind: RunKind = RunKind.FREE,
    /** The drone's note while [kind] is [RunKind.DRONE]. */
    val droneMidi: Int? = null,
    /** False while Capture is paused: readings are counted and never stored (CAP-01, CAP-03). */
    val captured: Boolean = true,
)
