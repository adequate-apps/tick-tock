CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE note_hist(
                run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
                midi_sounding INTEGER NOT NULL,
                n INTEGER NOT NULL,
                sum_cents REAL NOT NULL,
                sum_sq REAL NOT NULL,
                bins BLOB NOT NULL,
                n_attacks INTEGER NOT NULL DEFAULT 0,
                sum_attack_cents REAL NOT NULL DEFAULT 0,
                sum_attack_sq REAL NOT NULL DEFAULT 0,
                PRIMARY KEY (run_id, midi_sounding));

CREATE TABLE readings(
                run_id INTEGER NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
                hop INTEGER NOT NULL,
                midi_sounding INTEGER NOT NULL,
                hz REAL NOT NULL,
                cents REAL NOT NULL,
                ts INTEGER NOT NULL);

CREATE TABLE runs(
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
                drone_midi INTEGER);

CREATE TABLE sessions(
                id INTEGER PRIMARY KEY,
                started_at INTEGER NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                equipment TEXT NOT NULL DEFAULT '', purpose TEXT NOT NULL DEFAULT 'practice' CHECK (purpose IN ('practice', 'shop', 'equipment')), notes TEXT NOT NULL DEFAULT '');

CREATE INDEX readings_run ON readings(run_id);

CREATE INDEX readings_ts ON readings(ts);

CREATE INDEX runs_lookup ON runs(instrument_key, a4_hz, nickname);

CREATE INDEX runs_session ON runs(session_id);
