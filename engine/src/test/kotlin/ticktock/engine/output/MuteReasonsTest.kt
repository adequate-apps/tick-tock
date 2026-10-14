// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.MuteReason

class MuteReasonsTest {
    @Test
    fun `OUT-10 overlapping reasons in any order keep output muted until all have cleared`() {
        val reasons = MuteReasons()
        assertFalse(reasons.muted)

        assertTrue(reasons.set(MuteSource.CALL_RINGING, true))
        assertFalse(reasons.set(MuteSource.RECORDING_ON_SPEAKER, true))
        assertFalse(reasons.set(MuteSource.CALL_RINGING, false))
        assertTrue(reasons.muted)
        assertEquals(setOf(MuteSource.RECORDING_ON_SPEAKER), reasons.sources)

        assertTrue(reasons.set(MuteSource.RECORDING_ON_SPEAKER, false))
        assertFalse(reasons.muted)
        assertEquals(emptySet<MuteSource>(), reasons.sources)
    }

    @Test
    fun `OUT-10 clearing a reason that is not set changes nothing`() {
        val reasons = MuteReasons()
        assertFalse(reasons.set(MuteSource.BACKGROUND, false))
        assertFalse(reasons.muted)
        assertTrue(reasons.set(MuteSource.BACKGROUND, true))
        assertFalse(reasons.set(MuteSource.BACKGROUND, true))
        assertTrue(reasons.muted)
    }

    @Test
    fun `every contract reason maps to an engine source, and ringing is engine-only`() {
        assertEquals(MuteSource.RECORDING_ON_SPEAKER, MuteSource.of(MuteReason.RECORDING_ON_SPEAKER))
        assertEquals(MuteSource.BACKGROUND, MuteSource.of(MuteReason.BACKGROUND))
        assertEquals(MuteReason.entries.size + 1, MuteSource.entries.size)
        assertTrue(MuteSource.CALL_RINGING.name !in MuteReason.entries.map { it.name })
    }
}
