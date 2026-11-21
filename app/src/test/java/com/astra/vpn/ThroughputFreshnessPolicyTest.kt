package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputFreshnessPolicyTest {
    @Test
    fun freshSampleIsAcceptedWithinFiveMinutes() {
        val now = 1_800_000_000_000L
        val sample = ThroughputSample(80.0, 25.0, now - 60_000L)
        assertTrue(ThroughputFreshnessPolicy.isFresh(sample, now))
    }

    @Test
    fun staleFutureAndInvalidSamplesAreRejected() {
        val now = 1_800_000_000_000L
        assertFalse(ThroughputFreshnessPolicy.isFresh(
            ThroughputSample(80.0, 25.0, now - ThroughputFreshnessPolicy.MAX_AGE_MS - 1L), now
        ))
        assertFalse(ThroughputFreshnessPolicy.isFresh(ThroughputSample(80.0, 25.0, now + 60_000L), now))
        assertFalse(ThroughputFreshnessPolicy.isFresh(ThroughputSample(Double.NaN, 25.0, now), now))
        assertFalse(ThroughputFreshnessPolicy.isFresh(ThroughputSample(80.0, 0.0, now), now))
    }

    @Test
    fun throughputBonusIsBoundedAndNeverDominatesLatency() {
        val now = 1_800_000_000_000L
        val slow = ThroughputFreshnessPolicy.selectionBonusMs(ThroughputSample(1.0, 1.0, now), now)
        val fast = ThroughputFreshnessPolicy.selectionBonusMs(ThroughputSample(1_000.0, 1_000.0, now), now)
        assertTrue(fast > slow)
        assertTrue(fast <= ThroughputFreshnessPolicy.MAX_SELECTION_BONUS_MS)
        assertEquals(0L, ThroughputFreshnessPolicy.selectionBonusMs(
            ThroughputSample(1_000.0, 1_000.0, now - ThroughputFreshnessPolicy.MAX_AGE_MS - 1L), now
        ))
    }
}
