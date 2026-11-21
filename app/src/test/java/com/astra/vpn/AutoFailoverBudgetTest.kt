package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFailoverBudgetTest {
    @Test fun allowsOnlyConfiguredAttemptsWithinRollingWindow() {
        val budget = AutoFailoverBudget(maxAttempts = 3, windowMs = 600_000L)
        assertTrue(budget.recordAttempt(1_000L))
        assertTrue(budget.recordAttempt(61_000L))
        assertTrue(budget.recordAttempt(121_000L))
        assertFalse(budget.canAttempt(181_000L))
        assertEquals(3, budget.attemptsInWindow(181_000L))
    }

    @Test fun releasesBudgetWhenOldAttemptsLeaveWindow() {
        val budget = AutoFailoverBudget(maxAttempts = 2, windowMs = 100L)
        assertTrue(budget.recordAttempt(10L))
        assertTrue(budget.recordAttempt(20L))
        assertFalse(budget.canAttempt(30L))
        assertTrue(budget.canAttempt(111L))
    }

    @Test fun recordsOnlyWhenBudgetIsAvailable() {
        val budget = AutoFailoverBudget(maxAttempts = 1, windowMs = 1_000L)
        assertTrue(budget.recordAttempt(10L))
        assertFalse(budget.recordAttempt(20L))
        assertEquals(1, budget.attemptsInWindow(20L))
    }

    @Test fun rejectsNegativeTime() {
        val budget = AutoFailoverBudget()
        assertFalse(budget.canAttempt(-1L))
        assertFalse(budget.recordAttempt(-1L))
    }

    @Test fun backwardsClockClearsWindowInsteadOfPermanentLockout() {
        val budget = AutoFailoverBudget(maxAttempts = 1, windowMs = 1_000L)
        assertTrue(budget.recordAttempt(500L))
        assertFalse(budget.canAttempt(400L))
        assertEquals(0, budget.attemptsInWindow(400L))
        assertTrue(budget.canAttempt(401L))
    }
}
