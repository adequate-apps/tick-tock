// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.db

import android.database.sqlite.SQLiteConstraintException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SchemaTest {
    private val context = RuntimeEnvironment.getApplication()
    private val helper = HistoryDatabase(context, "schema-test.db")

    @After
    fun close() {
        helper.close()
        context.deleteDatabase("schema-test.db")
    }

    /**
     * SES-14: the schema a fresh install creates matches the committed golden
     * dump. A change to the schema is a new migration step plus a reviewed
     * golden update, never an edit in place.
     */
    @Test
    fun `SES-14 the created schema matches the golden dump`() {
        val actual = schemaDump(helper)
        val golden = File("src/test/resources/db/schema-v${Migrations.LATEST}.sql")
        if (!golden.isFile) {
            File("build/schema-v${Migrations.LATEST}.actual.sql").apply { parentFile.mkdirs() }.writeText(actual)
            fail("golden schema missing; review build/schema-v${Migrations.LATEST}.actual.sql and copy it to ${golden.path}")
        }
        assertEquals(golden.readText(), actual)
    }

    @Test
    fun `SES-14 upgrading version one retains saved sessions and reaches the fresh schema`() {
        context.openOrCreateDatabase("schema-test.db", 0, null).use { db ->
            HistoryDatabase.migrate(db, 0, 1)
            db.execSQL("INSERT INTO sessions(id, started_at, name, equipment) VALUES (7, 123, 'Study', 'Reed')")
            db.version = 1
        }
        val db = helper.readableDatabase
        assertEquals(2, db.version)
        db.rawQuery("SELECT id, started_at, name, equipment, purpose, notes FROM sessions", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(7L, c.getLong(0))
            assertEquals(123L, c.getLong(1))
            assertEquals(listOf("Study", "Reed", "practice", ""), (2..5).map(c::getString))
        }
        assertEquals(File("src/test/resources/db/schema-v2.sql").readText(), schemaDump(helper))
    }

    @Test
    fun `SES-01 the schema carries the 2_0 columns`() {
        val db = helper.readableDatabase
        fun columns(table: String) = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1)) }
        }
        assertTrue(columns("runs").containsAll(listOf("context", "target", "captured", "drone_midi", "nickname", "a4_gen")))
        assertTrue(columns("note_hist").containsAll(listOf("n_attacks", "sum_attack_cents", "sum_attack_sq", "bins")))
        assertTrue(columns("sessions").containsAll(listOf("equipment", "name")))
    }

    @Test
    fun `SES-08 indexes cover the run lookup and the timestamp`() {
        val db = helper.readableDatabase
        val indexes = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertTrue(indexes.containsAll(setOf("readings_ts", "runs_lookup")))
    }

    @Test
    fun `CTX-01 the run context and target only take their named values`() {
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO sessions(id, started_at) VALUES (1, 0)")
        try {
            db.execSQL("INSERT INTO runs(session_id, started_at, instrument_key, transposition, a4_hz, a4_gen, context) VALUES (1, 0, 'x', 0, 440, 0, 'concert')")
            fail("an unknown context was stored")
        } catch (expected: SQLiteConstraintException) {
            assertTrue(expected.message.orEmpty().contains("CHECK", ignoreCase = true))
        }
    }

    @Test
    fun `deleting a session takes its runs, summaries and readings with it`() {
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO sessions(id, started_at) VALUES (1, 0)")
        db.execSQL("INSERT INTO runs(id, session_id, started_at, instrument_key, transposition, a4_hz, a4_gen) VALUES (1, 1, 0, 'x', 0, 440, 0)")
        db.execSQL("INSERT INTO note_hist(run_id, midi_sounding, n, sum_cents, sum_sq, bins) VALUES (1, 69, 1, 0, 0, x'00')")
        db.execSQL("INSERT INTO readings(run_id, hop, midi_sounding, hz, cents, ts) VALUES (1, 0, 69, 440, 0, 0)")
        db.execSQL("DELETE FROM sessions WHERE id = 1")
        for (table in listOf("runs", "note_hist", "readings")) {
            val n = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
            assertEquals("$table rows left", 0, n)
        }
    }

    companion object {
        /** Every table and index definition, sorted, one per paragraph. */
        fun schemaDump(helper: HistoryDatabase): String =
            helper.readableDatabase.rawQuery(
                "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY type DESC, name",
                null,
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0).trim() + ";") } }.joinToString("\n\n", postfix = "\n")
    }
}
