package com.astra.vpn

import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputSessionPolicyTest {
    @Test
    fun watchdogUsesPersistedSessionDeadline() {
        assertEquals(5_000L, ThroughputSessionPolicy.remainingWatchdogDelayMillis(10_000L, 40_000L))
        assertEquals(0L, ThroughputSessionPolicy.remainingWatchdogDelayMillis(10_000L, 45_000L))
        assertEquals(0L, ThroughputSessionPolicy.remainingWatchdogDelayMillis(10_000L, 46_000L))
        assertEquals(0L, ThroughputSessionPolicy.remainingWatchdogDelayMillis(0L, 20_000L))
        assertEquals(0L, ThroughputSessionPolicy.remainingWatchdogDelayMillis(20_001L, 20_000L))
    }

    @Test
    fun watchdogExpiresOnlyAtOrAfterDeadlineAndRejectsInvalidClock() {
        assertFalse(ThroughputSessionPolicy.isWatchdogExpired(10_000L, 44_999L))
        assertTrue(ThroughputSessionPolicy.isWatchdogExpired(10_000L, 45_000L))
        assertTrue(ThroughputSessionPolicy.isWatchdogExpired(10_000L, 45_001L))
        assertTrue(ThroughputSessionPolicy.isWatchdogExpired(0L, 20_000L))
        assertTrue(ThroughputSessionPolicy.isWatchdogExpired(20_001L, 20_000L))
    }

    @Test
    fun onlyMatchingNonBlankSessionCanStopProbe() {
        assertTrue(ThroughputSessionPolicy.shouldStop("session-1", "session-1"))
        assertFalse(ThroughputSessionPolicy.shouldStop("session-2", "session-1"))
        assertFalse(ThroughputSessionPolicy.shouldStop("session-1", null))
        assertFalse(ThroughputSessionPolicy.shouldStop(null, "session-1"))
        assertFalse(ThroughputSessionPolicy.shouldStop("", ""))
    }
}
