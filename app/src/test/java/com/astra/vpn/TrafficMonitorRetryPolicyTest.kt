package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test fun constructorRejectsInvalidDelayConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            TrafficMonitorRetryPolicy(initialDelayMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrafficMonitorRetryPolicy(initialDelayMs = 2_000L, maxDelayMs = 1_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrafficMonitorRetryPolicy(stableConnectionMs = -1L)
        }
    }

    @Test fun doublingSaturatesWithoutLongOverflow() {
        val initial = Long.MAX_VALUE / 2L + 1L
        val policy = TrafficMonitorRetryPolicy(
            initialDelayMs = initial,
            maxDelayMs = Long.MAX_VALUE
        )
        assertEquals(initial, policy.nextDelayAfter(0L))
        assertEquals(Long.MAX_VALUE, policy.nextDelayAfter(0L))
        assertEquals(Long.MAX_VALUE, policy.nextDelayAfter(0L))
    }

    @Test fun negativeDurationIsTreatedAsUnstable() {
        val policy = TrafficMonitorRetryPolicy()
        assertEquals(1_000L, policy.nextDelayAfter(-1L))
        assertEquals(2_000L, policy.nextDelayAfter(-10L))
    }
}
