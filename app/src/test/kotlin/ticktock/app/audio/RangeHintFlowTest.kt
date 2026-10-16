// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import ticktock.core.ranges.EffectiveRange
import ticktock.core.ranges.RangeEnd
import ticktock.core.ranges.RangeLayer

class RangeHintFlowTest {
    private val end = RangeEnd(60, RangeLayer.BASE)
    private val hint = RangeHint("eb_alto", 0, EffectiveRange(end, end, end, end), RangeHintDirection.BELOW)

    @Test fun `old analysis cannot restore evidence after an invalidation with the same nullable value`() {
        val flow = RangeHintFlow()
        flow.invalidate(2)
        flow.publish(1, hint)
        assertNull(flow.value)
        flow.publish(2, hint)
        assertEquals(hint, flow.value)
    }

    @Test fun `a delayed invalidation preserves an already published current judgment`() {
        val flow = RangeHintFlow()
        flow.publish(2, hint)
        flow.invalidate(2)
        assertEquals(hint, flow.value)
        flow.invalidate(1)
        assertEquals(hint, flow.value)
        flow.invalidate(3)
        assertNull(flow.value)
    }

    @Test fun `a stale null publication cannot erase a newer judgment`() {
        val flow = RangeHintFlow()
        flow.publish(2, hint)
        flow.publish(1, null)
        assertEquals(hint, flow.value)
        assertEquals(listOf(hint), flow.replayCache)
        flow.publish(2, null)
        assertNull(flow.value)
    }

    @Test fun `collectors receive changed judgments without internal revision churn`() {
        val flow = RangeHintFlow()
        val seen = mutableListOf<RangeHint?>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            scope.launch { flow.collect { seen += it } }
            flow.invalidate(1)
            flow.publish(1, hint)
            flow.publish(2, hint.copy())
            flow.invalidate(2)
            flow.invalidate(3)
            assertEquals(listOf(null, hint, null), seen)
        } finally {
            scope.cancel()
        }
    }
}
