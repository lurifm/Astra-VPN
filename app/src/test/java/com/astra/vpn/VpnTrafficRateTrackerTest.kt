package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnTrafficRateTrackerTest {
    @Test
    fun firstSnapshotOnlyEstablishesBaseline() {
        val tracker = VpnTrafficRateTracker()
        assertNull(tracker.update(1_000_000L, 500_000L, 1_000L))
        assertNull(tracker.current(1_000L))
    }

    @Test
    fun computesDownloadAndUploadMbpsFromProxyByteDeltas() {
        val tracker = VpnTrafficRateTracker()
        tracker.update(1_000_000L, 500_000L, 1_000L)
        val rate = tracker.update(2_000_000L, 750_000L, 2_000L)!!

        assertEquals(8.0, rate.downloadMbps, 0.0001)
        assertEquals(2.0, rate.uploadMbps, 0.0001)
        assertEquals(0L, rate.sampleAgeMs)
    }

    @Test
    fun currentRateBecomesZeroWhenTrafficSnapshotGetsStale() {
        val tracker = VpnTrafficRateTracker(staleAfterMs = 2_000L)
        tracker.update(0L, 0L, 1_000L)
        tracker.update(1_000_000L, 250_000L, 2_000L)

        assertEquals(8.0, tracker.current(3_000L)!!.downloadMbps, 0.0001)
        val stale = tracker.current(4_001L)!!
        assertEquals(0.0, stale.downloadMbps, 0.0)
        assertEquals(0.0, stale.uploadMbps, 0.0)
        assertEquals(2_001L, stale.sampleAgeMs)
    }

    @Test
    fun counterRollbackRebaselinesAfterReconnect() {
        val tracker = VpnTrafficRateTracker()
        tracker.update(5_000L, 2_000L, 1_000L)
        assertNull(tracker.update(100L, 50L, 2_000L))
        val rate = tracker.update(1_000_100L, 250_050L, 3_000L)!!
        assertEquals(8.0, rate.downloadMbps, 0.0001)
        assertEquals(2.0, rate.uploadMbps, 0.0001)
    }

    @Test
    fun longPauseDoesNotCreateMisleadingAverageRate() {
        val tracker = VpnTrafficRateTracker(maxSampleGapMs = 2_000L)
        tracker.update(0L, 0L, 1_000L)
        assertNull(tracker.update(10_000_000L, 2_000_000L, 4_000L))
        assertNull(tracker.current(4_000L))
        assertEquals(8.0, tracker.update(11_000_000L, 2_250_000L, 5_000L)!!.downloadMbps, 0.0001)
    }

    @Test
    fun timestampRollbackRebaselines() {
        val tracker = VpnTrafficRateTracker()
        tracker.update(0L, 0L, 10_000L)
        assertNull(tracker.update(1_000_000L, 250_000L, 5_000L))
        assertNull(tracker.current(5_000L))
        assertEquals(8.0, tracker.update(2_000_000L, 500_000L, 6_000L)!!.downloadMbps, 0.0001)
    }

    @Test
    fun invalidCountersResetTheTracker() {
        val tracker = VpnTrafficRateTracker()
        tracker.update(0L, 0L, 1_000L)
        assertNull(tracker.update(-1L, 0L, 2_000L))
        assertNull(tracker.current(2_000L))
    }

    @Test
    fun resetClearsPreviousMeasurements() {
        val tracker = VpnTrafficRateTracker()
        tracker.update(0L, 0L, 1_000L)
        tracker.update(1_000_000L, 250_000L, 2_000L)
        tracker.reset()
        assertNull(tracker.current(3_000L))
        assertNull(tracker.update(2_000_000L, 500_000L, 4_000L))
    }
}
