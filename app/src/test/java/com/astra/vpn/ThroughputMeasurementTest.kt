package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThroughputMeasurementTest {
    @Test
    fun calculatesMbpsFromCompleteTransfer() {
        val sample = ThroughputMeasurement.fromTransfer(
            bytesRead = 1_048_576L,
            expectedBytes = 1_048_576L,
            durationNanos = 1_000_000_000L
        )
        assertEquals(8.388608, sample!!.downloadMbps, 0.000001)
    }

    @Test
    fun rejectsIncompleteTransfer() {
        assertNull(
            ThroughputMeasurement.fromTransfer(
                bytesRead = 900_000L,
                expectedBytes = 1_048_576L,
                durationNanos = 1_000_000_000L
            )
        )
    }

    @Test
    fun rejectsSamplesOutsideByteBounds() {
        assertNull(
            ThroughputMeasurement.fromTransfer(
                bytesRead = 100L,
                expectedBytes = 100L,
                durationNanos = 1_000_000_000L
            )
        )
        assertNull(
            ThroughputMeasurement.fromTransfer(
                bytesRead = 20L * 1024L * 1024L,
                expectedBytes = 20L * 1024L * 1024L,
                durationNanos = 1_000_000_000L
            )
        )
    }

    @Test
    fun acceptsFastReliableTransfersAndRejectsTimerResolutionNoise() {
        assertEquals(
            83.88608,
            ThroughputMeasurement.fromTransfer(
                bytesRead = 1_048_576L,
                expectedBytes = 1_048_576L,
                durationNanos = 100_000_000L
            )!!.downloadMbps,
            0.00001
        )
        assertNull(
            ThroughputMeasurement.fromTransfer(
                bytesRead = 1_048_576L,
                expectedBytes = 1_048_576L,
                durationNanos = 5_000_000L
            )
        )
        assertNull(
            ThroughputMeasurement.fromTransfer(
                bytesRead = 1_048_576L,
                expectedBytes = 1_048_576L,
                durationNanos = 61_000_000_000L
            )
        )
    }
}
