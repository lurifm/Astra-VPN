package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthCheckerTest {
    @Test fun acceptsSuccessfulAndRedirectResponses() {
        assertTrue(TunnelHealthChecker.isReachableStatusCode(200))
        assertTrue(TunnelHealthChecker.isReachableStatusCode(204))
        assertTrue(TunnelHealthChecker.isReachableStatusCode(301))
        assertTrue(TunnelHealthChecker.isReachableStatusCode(399))
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
}
