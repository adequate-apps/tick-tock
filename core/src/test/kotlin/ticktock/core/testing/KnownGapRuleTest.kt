// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.JUnitCore

/**
 * Runs small inner suites through JUnitCore so the rule's two directions are
 * observed from the outside: a failing gap reads as green, a passing gap as red.
 */
class KnownGapRuleTest {
    class StillFailing {
        @get:Rule
        val gaps = KnownGapRule()

        @Test
        @KnownGap("DET-00", "placeholder gap for the rule's own test")
        fun `a gap that still fails`() {
            fail("the gap is open")
        }

        @Test
        fun `an ordinary test beside it`() {
            assertEquals(2, 1 + 1)
        }
    }

    class Closed {
        @get:Rule
        val gaps = KnownGapRule()

        @Test
        @KnownGap("DET-00", "placeholder gap for the rule's own test")
        fun `a gap that passes`() {
            assertEquals(2, 1 + 1)
        }
    }

    class Skipping {
        @get:Rule
        val gaps = KnownGapRule()

        @Test
        @KnownGap("DET-00", "placeholder gap for the rule's own test")
        fun `a gap that hides behind an assumption`() {
            assumeTrue(false)
        }
    }

    @Test
    fun `a failing gap counts as green and leaves other tests alone`() {
        val result = JUnitCore.runClasses(StillFailing::class.java)
        assertEquals(2, result.runCount)
        assertEquals(0, result.failureCount)
    }

    @Test
    fun `a gap that passes fails the suite and names the criterion`() {
        val result = JUnitCore.runClasses(Closed::class.java)
        assertEquals(1, result.failureCount)
        val message = result.failures.single().message.orEmpty()
        assertTrue(message, message.contains("DET-00"))
        assertTrue(message, message.contains("remove the @KnownGap"))
    }

    @Test
    fun `a gap that skips is reported as skipped, never as a failing gap`() {
        val result = JUnitCore.runClasses(Skipping::class.java)
        assertEquals(0, result.failureCount)
        assertEquals(1, result.ignoreCount + result.assumptionFailureCount)
    }
}
