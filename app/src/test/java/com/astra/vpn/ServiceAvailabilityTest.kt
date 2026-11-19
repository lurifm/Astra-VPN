package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceAvailabilityTest {
    @Test fun classifiesProbeResults() {
        assertEquals(ServiceAvailability.State.UNKNOWN, ServiceAvailability.classify(ServiceAvailability.Probe(0, 0)))
        assertEquals(ServiceAvailability.State.AVAILABLE, ServiceAvailability.classify(ServiceAvailability.Probe(2, 2)))
        assertEquals(ServiceAvailability.State.DEGRADED, ServiceAvailability.classify(ServiceAvailability.Probe(1, 2)))
        assertEquals(ServiceAvailability.State.UNAVAILABLE, ServiceAvailability.classify(ServiceAvailability.Probe(0, 2)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsImpossibleProbeCounts() {
        ServiceAvailability.Probe(3, 2)
    }
}
