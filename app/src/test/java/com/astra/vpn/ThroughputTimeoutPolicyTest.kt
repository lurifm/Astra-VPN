package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ThroughputTimeoutPolicyTest {
    @Test
    fun expiredDeadlineReturnsZero() {
        assertEquals(0, ThroughputTimeoutPolicy.remainingMillis(100L, 100L))
        assertEquals(0, ThroughputTimeoutPolicy.remainingMillis(99L, 100L))
    }

    @Test
    fun positiveSubMillisecondBudgetRoundsUpToOne() {
        assertEquals(1, ThroughputTimeoutPolicy.remainingMillis(1_000_001L, 1_000_000L))
    }

    @Test
    fun millisecondsAreRoundedUpAndCapped() {
        assertEquals(2, ThroughputTimeoutPolicy.remainingMillis(2_000_001L, 1_000_000L))
        assertEquals(Int.MAX_VALUE, ThroughputTimeoutPolicy.remainingMillis(Long.MAX_VALUE, 0L))
    }
}
