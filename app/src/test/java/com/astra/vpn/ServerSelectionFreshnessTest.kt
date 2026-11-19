package com.astra.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSelectionFreshnessTest {
    @Test fun acceptsFreshMeasurementsAtBoundary() {
        val now = 1_000_000L
        assertTrue(ServerSelectionFreshness.usableLatency(now - 600_000L, now))
        assertTrue(ServerSelectionFreshness.usableThroughput(now - 3_600_000L, now))
    }

    @Test fun rejectsStaleFutureAndMissingMeasurements() {
        val now = 1_000_000L
        assertFalse(ServerSelectionFreshness.usableLatency(now - 600_001L, now))
        assertFalse(ServerSelectionFreshness.usableThroughput(now + 1L, now))
        assertFalse(ServerSelectionFreshness.usableLatency(0L, now))
    }
}
