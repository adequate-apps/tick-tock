// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.music

import org.junit.Assert.assertEquals
import org.junit.Test

class WholeCentsTest {
    @Test fun `BND-06 signed fractional cents format as whole numbers including half boundaries`() {
        val cases = listOf(-49.6 to "-50", -12.5 to "-13", -8.6 to "-9", -0.5 to "-1",
            -0.4 to "+0", 0.0 to "+0", 0.4 to "+0", 0.5 to "+1", 3.6 to "+4", 12.5 to "+13", 49.6 to "+50")
        for ((raw, expected) in cases) assertEquals("cents $raw", expected, Cents.format(Cents.shown(raw)))
        assertEquals("–", Cents.format(Cents.shown(Double.NaN)))
    }
}
