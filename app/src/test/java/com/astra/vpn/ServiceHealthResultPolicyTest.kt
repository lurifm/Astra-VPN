package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHealthResultPolicyTest {
    @Test fun acceptsFreshMonotonicResult() {
        assertTrue(ServiceHealthResultPolicy.isFresh(10_000L, 25_000L))
        assertTrue(ServiceHealthResultPolicy.isFresh(10_000L, 40_000L))
    }

    @Test fun publishesOnlyWhileSameVpnNetworkAndServiceAreActive() {
        assertTrue(ServiceHealthResultPolicy.shouldPublish(true, true))
        assertFalse(ServiceHealthResultPolicy.shouldPublish(false, true))
        assertFalse(ServiceHealthResultPolicy.shouldPublish(true, false))
        assertFalse(ServiceHealthResultPolicy.shouldPublish(false, false))
    }

    @Test fun rejectsResultFromObsoleteProbeGeneration() {
        assertTrue(ServiceHealthResultPolicy.shouldPublish(true, true, true))
        assertFalse(ServiceHealthResultPolicy.shouldPublish(true, true, false))
    }

    @Test fun rejectsMissingFutureAndExpiredTimestamps() {
        assertFalse(ServiceHealthResultPolicy.isFresh(0L, 1_000L))
        assertFalse(ServiceHealthResultPolicy.isFresh(-1L, 1_000L))
        assertFalse(ServiceHealthResultPolicy.isFresh(2_000L, 1_000L))
        assertFalse(ServiceHealthResultPolicy.isFresh(1_000L, 31_001L))
    }
}
