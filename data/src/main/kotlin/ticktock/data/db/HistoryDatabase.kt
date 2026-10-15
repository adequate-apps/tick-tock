// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ticktock.data.Storage

/**
 * The history database: framework SQLite with hand-written SQL (PLAN 3.4).
 * Only the storage thread opens it for writing (SES-11's one-writer rule
 * applies to history too). Foreign keys are on so deleting a session takes
 * its runs, summaries and raw readings with it.
 */
class HistoryDatabase(context: Context, name: String? = Storage.DATABASE_FILE) :
    SQLiteOpenHelper(context, name, null, Migrations.LATEST) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = migrate(db, 0, Migrations.LATEST)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = migrate(db, oldVersion, newVersion)

    companion object {
        /** Runs every step from [from] up to [to]; the caller holds the transaction. */
        fun migrate(db: SQLiteDatabase, from: Int, to: Int) {
            for (step in Migrations.ALL) {
                if (step.from >= from && step.to <= to) step.statements.forEach(db::execSQL)
            }
        }
    }
}
