package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficMonitorRetryPolicyTest {
    @Test fun shortLivedFailuresIncreaseDelayExponentially() {
        val policy = TrafficMonitorRetryPolicy()
        assertEquals(1_000L, policy.nextDelayAfter(500L))
        assertEquals(2_000L, policy.nextDelayAfter(2_000L))
        assertEquals(4_000L, policy.nextDelayAfter(0L))
    }

    @Test fun delayNeverExceedsThirtySeconds() {
        val policy = TrafficMonitorRetryPolicy()
        repeat(10) { policy.nextDelayAfter(0L) }
        assertEquals(30_000L, policy.nextDelayAfter(0L))
        assertEquals(30_000L, policy.nextDelayAfter(0L))
    }

    @Test fun stableConnectionResetsBackoff() {
        val policy = TrafficMonitorRetryPolicy()
        assertEquals(1_000L, policy.nextDelayAfter(0L))
        assertEquals(2_000L, policy.nextDelayAfter(0L))
        assertEquals(1_000L, policy.nextDelayAfter(30_000L))
        assertEquals(1_000L, policy.nextDelayAfter(100_000L))
    }

    @Test fun negativeDurationIsTreatedAsUnstable() {
        val policy = TrafficMonitorRetryPolicy()
        assertEquals(1_000L, policy.nextDelayAfter(-1L))
        assertEquals(2_000L, policy.nextDelayAfter(-10L))
    }
}
