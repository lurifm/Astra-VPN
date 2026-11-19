package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceReachabilityCheckerTest {
    @Test fun resultSeparatesHostReachabilityFromServiceAvailability() {
        assertTrue(ServiceReachabilityChecker.Result("test", 204, 12L, null).reachable)
        assertTrue(ServiceReachabilityChecker.Result("test", 204, 12L, null).serviceAvailable)
        assertTrue(ServiceReachabilityChecker.Result("test", 302, 12L, null).reachable)
        assertTrue(ServiceReachabilityChecker.Result("test", 302, 12L, null).serviceAvailable)

        for (code in listOf(403, 503)) {
            val result = ServiceReachabilityChecker.Result("test", code, 12L, null)
            assertTrue(result.reachable)
            assertFalse(result.serviceAvailable)
            assertTrue(result.display().contains("сервер ответил"))
            assertTrue(result.display().contains("сервис недоступен"))
            assertFalse(result.display().contains("доступен ·"))
        }

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

    @Test fun rejectsClientAndServerErrorsAsAvailableServices() {
        assertFalse(ServiceReachabilityChecker.isSuccessfulHttpCode(403))
        assertFalse(ServiceReachabilityChecker.isSuccessfulHttpCode(404))
        assertFalse(ServiceReachabilityChecker.isSuccessfulHttpCode(503))
    }
}
