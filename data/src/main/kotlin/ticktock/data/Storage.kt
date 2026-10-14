// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.data

/**
 * Names shared by every storage component. The schema itself, the migrations
 * list and the stores arrive in M3; keeping the file names here means the
 * crash-log offer and the Diagnostics page can refer to them from day one.
 */
object Storage {
    const val DATABASE_FILE = "history.db"
    const val PREFS_FILE = "prefs.json"
    const val CRASH_LOG_FILE = "crash.log"

    /** Hard cap on the crash log so a crash loop cannot fill the disk (ERR-02). */
    const val CRASH_LOG_MAX_BYTES = 256 * 1024
}
