// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import ticktock.core.prefs.Prefs
import ticktock.core.prefs.PrefsSchema
import ticktock.data.Storage
import java.io.File

/**
 * Saved prefs that have finished first run, written before the activity
 * starts so a test opens on the tabs. A test of first run itself skips this.
 */
fun skipFirstRun(prefs: Prefs = Prefs(firstRunComplete = true)) {
    val dir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
    File(dir, Storage.PREFS_FILE).writeText(PrefsSchema.write(prefs))
}

/** [skipFirstRun] as a rule, for tests whose compose rule starts the activity itself; order it before that rule. */
class SkipFirstRunRule : ExternalResource() {
    override fun before() = skipFirstRun()
}
