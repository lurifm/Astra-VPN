package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class BankingResumeRetryPolicyTest {
    @Test fun allowsFirstAttemptAndEnforcesTenSecondCooldown() {
        assertEquals(true, BankingResumeRetryPolicy.shouldAttempt(0L, 20_000L))
        assertEquals(false, BankingResumeRetryPolicy.shouldAttempt(20_000L, 20_500L))
        assertEquals(false, BankingResumeRetryPolicy.shouldAttempt(20_000L, 29_999L))
        assertEquals(true, BankingResumeRetryPolicy.shouldAttempt(20_000L, 30_000L))
    }

    @Test fun clockRollbackFailsClosed() {
        assertEquals(false, BankingResumeRetryPolicy.shouldAttempt(30_000L, 29_000L))
    }
}
