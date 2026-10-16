// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal object AudioEvidence {
    fun directory(): File {
        val run = InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        require(run.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")))
        return File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "audio-validation/$run")
            .also { check(it.isDirectory || it.mkdirs()) }
    }
}
