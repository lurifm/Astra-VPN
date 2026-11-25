package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceTrafficRateTrackerTest {
    @Test fun firstSampleEstablishesBaselineAndSecondCalculatesMbps() {
        val tracker = DeviceTrafficRateTracker()
        assertNull(tracker.update(1_000_000L, 500_000L, 1_000L))
        val rate = tracker.update(2_000_000L, 1_000_000L, 2_000L)!!
        assertEquals(8.0, rate.downloadMbps, 0.0001)
        assertEquals(4.0, rate.uploadMbps, 0.0001)
    }

    @Test fun rejectsSamplesAcrossLongActivityPause() {
        val tracker = DeviceTrafficRateTracker()
        assertNull(tracker.update(1_000L, 1_000L, 1_000L))
        assertNull(tracker.update(9_000L, 9_000L, 7_000L))
        val rate = tracker.update(10_000L, 10_000L, 8_000L)!!
        assertEquals(0.008, rate.downloadMbps, 0.0001)
    }

    @Test fun counterResetEstablishesNewBaseline() {
        val tracker = DeviceTrafficRateTracker()
        assertNull(tracker.update(10_000L, 10_000L, 1_000L))
        assertNull(tracker.update(100L, 100L, 2_000L))
        val rate = tracker.update(1_000_100L, 500_100L, 3_000L)!!
        assertEquals(8.0, rate.downloadMbps, 0.0001)
        assertEquals(4.0, rate.uploadMbps, 0.0001)
    }

    @Test fun elapsedClockRollbackCannotCreateRate() {
        val tracker = DeviceTrafficRateTracker()
        assertNull(tracker.update(1_000L, 1_000L, 10_000L))
        assertNull(tracker.update(9_000L, 9_000L, 5_000L))
        val rate = tracker.update(10_000L, 10_000L, 6_000L)!!
        assertEquals(0.008, rate.downloadMbps, 0.0001)
    }

    @Test fun invalidCountersResetTracker() {
        val tracker = DeviceTrafficRateTracker()
        assertNull(tracker.update(1_000L, 1_000L, 1_000L))
        assertNull(tracker.update(-1L, 2_000L, 2_000L))
        assertNull(tracker.update(5_000L, 5_000L, 3_000L))
        val rate = tracker.update(6_000L, 7_000L, 4_000L)!!
        assertEquals(0.008, rate.downloadMbps, 0.0001)
        assertEquals(0.016, rate.uploadMbps, 0.0001)
    }

    @Test fun constructorRejectsNonPositiveSampleGap() {
        val failure = runCatching { DeviceTrafficRateTracker(0L) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, failure?.javaClass)
    }
}
