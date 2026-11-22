package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientConnectRetryPolicyTest {
    @Test fun schedulesFourBoundedRetriesWithIncreasingDelays() {
        assertEquals(3_000L, TransientConnectRetryPolicy.delayForAttempt(0))
        assertEquals(8_000L, TransientConnectRetryPolicy.delayForAttempt(1))
        assertEquals(15_000L, TransientConnectRetryPolicy.delayForAttempt(2))
        assertEquals(30_000L, TransientConnectRetryPolicy.delayForAttempt(3))
    }

    @Test fun refusesRetryAfterFourAttempts() {
        assertNull(TransientConnectRetryPolicy.delayForAttempt(4))
    }

    @Test fun negativeAttemptUsesFirstDelay() {
        assertEquals(3_000L, TransientConnectRetryPolicy.delayForAttempt(-1))
    }

    @Test fun retryIsLimitedToEligibleManualConnects() {
        assertTrue(TransientConnectRetryPolicy.shouldSchedule(true, false, false, true, 0))
        assertFalse(TransientConnectRetryPolicy.shouldSchedule(false, false, false, true, 0))
        assertFalse(TransientConnectRetryPolicy.shouldSchedule(true, true, false, true, 0))
        assertFalse(TransientConnectRetryPolicy.shouldSchedule(true, false, true, true, 0))
        assertFalse(TransientConnectRetryPolicy.shouldSchedule(true, false, false, false, 0))
        assertTrue(TransientConnectRetryPolicy.shouldSchedule(true, false, false, true, 3))
        assertFalse(TransientConnectRetryPolicy.shouldSchedule(true, false, false, true, 4))
    }

    @Test fun backgroundRetryWaitsForActivityToReturnWithoutBeingDiscarded() {
        assertTrue(TransientConnectRetryPolicy.shouldDeferUntilActivityStarted(false, false, false))
        assertFalse(TransientConnectRetryPolicy.shouldDeferUntilActivityStarted(true, false, false))
        assertFalse(TransientConnectRetryPolicy.shouldDeferUntilActivityStarted(false, true, false))
        assertFalse(TransientConnectRetryPolicy.shouldDeferUntilActivityStarted(false, false, true))
    }

    @Test fun staleOrUserCancelledRetriesCannotExecute() {
        assertTrue(TransientConnectRetryPolicy.shouldExecute(true, true, true, false, false, false, false, true))
        assertFalse(TransientConnectRetryPolicy.shouldExecute(false, true, true, false, false, false, false, true))
        assertFalse(TransientConnectRetryPolicy.shouldExecute(true, false, true, false, false, false, false, true))
        assertFalse(TransientConnectRetryPolicy.shouldExecute(true, true, false, false, false, false, false, true))
        assertFalse(TransientConnectRetryPolicy.shouldExecute(true, true, true, true, false, false, false, true))
        assertFalse(TransientConnectRetryPolicy.shouldExecute(true, true, true, false, true, false, false, true))
        assertFalse(TransientConnectRetryPolicy.shouldExecute(true, true, true, false, false, true, false, true))
        assertFalse(TransientConnectRetryPolicy.shouldExecute(true, true, true, false, false, false, true, true))
        assertFalse(TransientConnectRetryPolicy.shouldExecute(true, true, true, false, false, false, false, false))
    }
}
