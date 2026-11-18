package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceReachabilityCheckerTest {
    @Test fun acceptsSuccessfulAndRedirectResponses() {
        assertTrue(ServiceReachabilityChecker.isSuccessfulHttpCode(200))
        assertTrue(ServiceReachabilityChecker.isSuccessfulHttpCode(204))
        assertTrue(ServiceReachabilityChecker.isSuccessfulHttpCode(302))
    }

    @Test fun rejectsClientAndServerErrors() {
        assertFalse(ServiceReachabilityChecker.isSuccessfulHttpCode(403))
        assertFalse(ServiceReachabilityChecker.isSuccessfulHttpCode(404))
        assertFalse(ServiceReachabilityChecker.isSuccessfulHttpCode(503))
    }
}
