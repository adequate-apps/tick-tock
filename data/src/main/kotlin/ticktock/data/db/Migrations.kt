// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.db

/**
 * One step from [from] to `from + 1`, as plain SQL run inside the upgrade
 * transaction. Steps are never edited once shipped; a change is a new step.
 */
class Migration(val from: Int, val statements: List<String>) {
    val to: Int get() = from + 1
}

/**
 * The ordered list of schema steps. A fresh install runs every step from
 * version 0, so a new database and an upgraded one take the same path and the
 * golden schema test covers both.
 */
object Migrations {
    /**
     * Version 1, the 2.0 schema (PLAN 3.4 plus the SCOPE-REVISION columns).
     *
     * - `sessions.name` is empty until the player saves the session; a named
     *   session is a saved one. `equipment` is EQP-01.
     * - `runs.context`, `runs.target` (CTX-01) and `runs.captured` (CAP-03)
     *   describe how the run was played; `drone_midi` is the drone note of a
     *   drone run (CTX-02), null otherwise.
     * - `note_hist` is the per-run, per-note summary kept after raw readings
     *   age out (SES-08, SES-09): counts and sums, a 101-bin histogram of whole
     *   cents over -50..+50 as little-endian u16, and the attack sums (ATK-01).
     * - `readings` holds raw rows for the retention period only.
     */
    private val v1 = Migration(
        from = 0,
        statements = listOf(
            """CREATE TABLE sessions(
                id INTEGER PRIMARY KEY,
                started_at INTEGER NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                equipment TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE runs(
                id INTEGER PRIMARY KEY,
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                started_at INTEGER NOT NULL,
                instrument_key TEXT NOT NULL,
                transposition INTEGER NOT NULL,
                a4_hz REAL NOT NULL,
                a4_gen INTEGER NOT NULL,
                nickname TEXT NOT NULL DEFAULT '',
                context TEXT NOT NULL DEFAULT 'free' CHECK (context IN ('free', 'drone', 'exercise')),
                target TEXT NOT NULL DEFAULT 'equal' CHECK (target IN ('equal', 'just')),
                captured INTEGER NOT NULL DEFAULT 1 CHECK (captured IN (0, 1)),
                drone_midi INTEGER)""",
            """CREATE TABLE note_hist(
                run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
                midi_sounding INTEGER NOT NULL,
                n INTEGER NOT NULL,
                sum_cents REAL NOT NULL,
                sum_sq REAL NOT NULL,
                bins BLOB NOT NULL,
                n_attacks INTEGER NOT NULL DEFAULT 0,
                sum_attack_cents REAL NOT NULL DEFAULT 0,
                sum_attack_sq REAL NOT NULL DEFAULT 0,
                PRIMARY KEY (run_id, midi_sounding))""",
            """CREATE TABLE readings(
                run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
                hop INTEGER NOT NULL,
                midi_sounding INTEGER NOT NULL,
                hz REAL NOT NULL,
                cents REAL NOT NULL,
                ts INTEGER NOT NULL)""",
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE INDEX readings_ts ON readings(ts)",
            "CREATE INDEX readings_run ON readings(run_id)",
            "CREATE INDEX runs_session ON runs(session_id)",
            "CREATE INDEX runs_lookup ON runs(instrument_key, a4_hz, nickname)",
        ),
    )

    private val v2 = Migration(
        from = 1,
        statements = listOf(
            "ALTER TABLE sessions ADD COLUMN purpose TEXT NOT NULL DEFAULT 'practice' CHECK (purpose IN ('practice', 'shop', 'equipment'))",
            "ALTER TABLE sessions ADD COLUMN notes TEXT NOT NULL DEFAULT ''",
        ),
    )

    val ALL: List<Migration> = listOf(v1, v2)

    /** The version a database has after every step has run. */
    val LATEST: Int = ALL.last().to

    init {
        ALL.forEachIndexed { index, step -> check(step.from == index) { "migration steps must be contiguous from 0" } }
    }
}
