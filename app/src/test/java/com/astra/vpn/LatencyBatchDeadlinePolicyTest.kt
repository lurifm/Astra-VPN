package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyBatchDeadlinePolicyTest {
    @Test
    fun deadlineIncludesStartupAndProbeTimeInOneBudget() {
        val deadline = LatencyBatchDeadlinePolicy.deadlineAt(1_000L, 10_000L)

        assertEquals(6_000L, LatencyBatchDeadlinePolicy.remainingMs(deadline, 5_000L))
        assertEquals(0L, LatencyBatchDeadlinePolicy.remainingMs(deadline, 11_000L))
    }

    @Test
    fun deadlineExpiresExactlyAtBoundary() {
        val deadline = LatencyBatchDeadlinePolicy.deadlineAt(100L, 10L)

        assertEquals(1L, LatencyBatchDeadlinePolicy.remainingMs(deadline, 109L))
        assertEquals(0L, LatencyBatchDeadlinePolicy.remainingMs(deadline, 110L))
    }

    @Test
    fun invalidClockAndExpiredDeadlineNeverProduceNegativeDelay() {
        assertEquals(0L, LatencyBatchDeadlinePolicy.remainingMs(100L, -1L))
        assertEquals(0L, LatencyBatchDeadlinePolicy.remainingMs(100L, 100L))
        assertEquals(0L, LatencyBatchDeadlinePolicy.remainingMs(0L, 0L))
    }

    @Test
    fun deadlineSaturatesInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            LatencyBatchDeadlinePolicy.deadlineAt(Long.MAX_VALUE - 5L, 10L)
        )
    }

    @Test
    fun nonPositiveTimeoutCreatesImmediateDeadline() {
        assertEquals(50L, LatencyBatchDeadlinePolicy.deadlineAt(50L, 0L))
        assertEquals(0L, LatencyBatchDeadlinePolicy.remainingMs(50L, 50L))
    }
}
