package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthCheckerTest {
    @Test fun acceptsSuccessfulResponsesAndRejectsRedirects() {
        assertTrue(TunnelHealthChecker.isReachableStatusCode(200))
        assertTrue(TunnelHealthChecker.isReachableStatusCode(201))
        assertTrue(TunnelHealthChecker.isReachableStatusCode(204))
        assertTrue(TunnelHealthChecker.isReachableStatusCode(299))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(300))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(301))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(302))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(307))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(308))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(399))
    }

    @Test fun rejectsClientAndServerErrors() {
        assertFalse(TunnelHealthChecker.isReachableStatusCode(400))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(403))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(404))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(503))
    }

    @Test fun rejectsInvalidStatusCodes() {
        assertFalse(TunnelHealthChecker.isReachableStatusCode(0))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(100))
        assertFalse(TunnelHealthChecker.isReachableStatusCode(600))
    }

    @Test fun dividesTenSecondBudgetAcrossBothEndpointsAndPhases() {
        assertEquals(2_500, TunnelHealthTimeoutPolicy.timeoutPerPhaseMs(10_000L, 2))
        assertEquals(2_500, TunnelHealthTimeoutPolicy.timeoutPerPhaseMs(5_000L, 1))
    }

    @Test fun reducesTimeoutAsFallbackBudgetShrinks() {
        assertEquals(1_000, TunnelHealthTimeoutPolicy.timeoutPerPhaseMs(4_000L, 2))
        assertEquals(500, TunnelHealthTimeoutPolicy.timeoutPerPhaseMs(1_000L, 1))
    }

    @Test fun rejectsExpiredBudgetAndNoRemainingEndpoints() {
        assertEquals(0, TunnelHealthTimeoutPolicy.timeoutPerPhaseMs(0L, 2))
        assertEquals(0, TunnelHealthTimeoutPolicy.timeoutPerPhaseMs(-1L, 1))
        assertEquals(0, TunnelHealthTimeoutPolicy.timeoutPerPhaseMs(1_000L, 0))
    }

    @Test fun tinyPositiveBudgetStillProducesPositiveSocketTimeout() {
        assertEquals(1, TunnelHealthTimeoutPolicy.timeoutPerPhaseMs(1L, 2))
    }

}
