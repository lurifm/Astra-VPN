package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyCacheFreshnessPolicyTest {
    @Test
    fun acceptsCacheWithinFiveMinuteWindow() {
        val now = 1_000_000L
        assertTrue(LatencyCacheFreshnessPolicy.isFresh(now - 299_999L, now))
        assertTrue(LatencyCacheFreshnessPolicy.isFresh(now - 300_000L, now))
    }

    @Test
    fun rejectsExpiredFutureAndMissingTimestamps() {
        val now = 1_000_000L
        assertFalse(LatencyCacheFreshnessPolicy.isFresh(now - 300_001L, now))
        assertFalse(LatencyCacheFreshnessPolicy.isFresh(now + 1L, now))
        assertFalse(LatencyCacheFreshnessPolicy.isFresh(0L, now))
        assertFalse(LatencyCacheFreshnessPolicy.isFresh(now, 0L))
    }

    @Test
    fun rejectsInvalidMaximumAge() {
        assertFalse(LatencyCacheFreshnessPolicy.isFresh(100L, 100L, 0L))
    }
}
