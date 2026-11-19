package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceReachabilityCheckerTest {
    @Test fun resultUsesTheSameReachabilityRule() {
        assertTrue(ServiceReachabilityChecker.Result("test", 204, 12L, null).reachable)
        assertTrue(ServiceReachabilityChecker.Result("test", 302, 12L, null).reachable)
        assertTrue(ServiceReachabilityChecker.Result("test", 503, 12L, null).reachable)
        assertFalse(ServiceReachabilityChecker.Result("test", 503, 12L, null).serviceAvailable)
        assertTrue(ServiceReachabilityChecker.Result("test", 503, 12L, null).display().contains("сервер ответил"))
        assertFalse(ServiceReachabilityChecker.Result("test", 503, 12L, null).display().contains("доступен"))
        assertTrue(ServiceReachabilityChecker.Result("test", 403, 12L, null).reachable)
        assertFalse(ServiceReachabilityChecker.Result("test", 403, 12L, null).serviceAvailable)
        assertTrue(ServiceReachabilityChecker.Result("test", 403, 12L, null).display().contains("сервер ответил"))
        assertFalse(ServiceReachabilityChecker.Result("test", null, null, "IOException").reachable)
    }

    @Test fun missingErrorUsesReadableFallback() {
        assertTrue(ServiceReachabilityChecker.Result("test", null, null, null).display().contains("неизвестная ошибка"))
    }

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
