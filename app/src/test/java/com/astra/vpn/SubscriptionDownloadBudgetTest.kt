package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDownloadBudgetTest {
    @Test fun aggregateByteBudgetIsSharedAcrossMultipleResponses() {
        val budget = SubscriptionDownloadBudget(maxBytes = 10, timeoutMillis = 1_000)
        budget.consume(4)
        budget.consume(6)
        assertEquals(0L, budget.remainingBytes())
        assertThrows(IllegalArgumentException::class.java) { budget.consume(1) }
    }

    @Test fun allowsExactlyTheConfiguredByteLimit() {
        val budget = SubscriptionDownloadBudget(maxBytes = 8, timeoutMillis = 1_000)
        budget.consume(8)
        assertEquals(0L, budget.remainingBytes())
    }

    @Test fun rejectsNegativeByteCounts() {
        val budget = SubscriptionDownloadBudget(maxBytes = 8, timeoutMillis = 1_000)
        assertThrows(IllegalArgumentException::class.java) { budget.consume(-1) }
    }

    @Test fun timeoutIsCappedByRemainingBatchDeadline() {
        var now = 1_000_000L
        val budget = SubscriptionDownloadBudget(maxBytes = 100, timeoutMillis = 100) { now }
        assertEquals(100, budget.remainingTimeoutMillis(15_000))
        now += 40_000_000L
        assertEquals(60, budget.remainingTimeoutMillis(15_000))
        assertEquals(20, budget.remainingTimeoutMillis(20))
    }

    @Test fun expiredDeadlineRejectsFurtherReadsAndConsumes() {
        var now = 0L
        val budget = SubscriptionDownloadBudget(maxBytes = 100, timeoutMillis = 10) { now }
        now = 10_000_000L
        val error = assertThrows(IllegalStateException::class.java) {
            budget.remainingTimeoutMillis(15_000)
        }
        assertTrue(error.message!!.contains("время"))
        assertThrows(IllegalStateException::class.java) { budget.consume(1) }
    }

    @Test fun batchBudgetAllowsTwoMiBPerSubscription() {
        val budget = SubscriptionDownloadBudget.forSubscriptionCount(3, timeoutMillis = 1_000)
        repeat(3) { budget.consume((2L * 1024L * 1024L).toInt()) }
        assertEquals(0L, budget.remainingBytes())
    }

    @Test fun batchBudgetRejectsNegativeSubscriptionCount() {
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionDownloadBudget.forSubscriptionCount(-1)
        }
    }

    @Test fun refusesInvalidBudgetConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { SubscriptionDownloadBudget(maxBytes = -1) }
        assertThrows(IllegalArgumentException::class.java) { SubscriptionDownloadBudget(timeoutMillis = 0) }
    }
}
