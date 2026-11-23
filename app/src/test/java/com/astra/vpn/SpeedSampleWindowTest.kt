package com.astra.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedSampleWindowTest {
    @Test fun computesAverageAndPeakForBothDirections() {
        val window = SpeedSampleWindow(5)
        window.add(10f, 2f)
        window.add(20f, 6f)
        val stats = window.stats()
        assertEquals(15f, stats.averageDownloadMbps, 0.001f)
        assertEquals(4f, stats.averageUploadMbps, 0.001f)
        assertEquals(20f, stats.peakDownloadMbps, 0.001f)
        assertEquals(6f, stats.peakUploadMbps, 0.001f)
        assertEquals(2, stats.sampleCount)
    }

    @Test fun keepsOnlyMostRecentSamples() {
        val window = SpeedSampleWindow(2)
        window.add(10f, 1f)
        window.add(20f, 2f)
        window.add(30f, 3f)
        val stats = window.stats()
        assertEquals(25f, stats.averageDownloadMbps, 0.001f)
        assertEquals(30f, stats.peakDownloadMbps, 0.001f)
        assertEquals(2, stats.sampleCount)
    }

    @Test fun clampsNegativeMeasurementsAndHandlesEmptyWindow() {
        val window = SpeedSampleWindow()
        assertEquals(0, window.stats().sampleCount)
        window.add(-5f, -1f)
        val stats = window.stats()
        assertEquals(0f, stats.averageDownloadMbps, 0.001f)
        assertEquals(0f, stats.peakUploadMbps, 0.001f)
    }
    @Test fun replacesNonFiniteMeasurementsWithoutPoisoningStatistics() {
        val window = SpeedSampleWindow(4)
        window.add(Float.NaN, Float.POSITIVE_INFINITY)
        window.add(Float.NEGATIVE_INFINITY, 8f)

        val stats = window.stats()
        assertEquals(0f, stats.averageDownloadMbps, 0.001f)
        assertEquals(4f, stats.averageUploadMbps, 0.001f)
        assertEquals(0f, stats.peakDownloadMbps, 0.001f)
        assertEquals(8f, stats.peakUploadMbps, 0.001f)
        assertEquals(2, stats.sampleCount)
    }

    @Test fun finiteMeasurementsRemainUnchanged() {
        val window = SpeedSampleWindow(2)
        window.add(12.5f, 3.5f)
        val stats = window.stats()
        assertEquals(12.5f, stats.averageDownloadMbps, 0.001f)
        assertEquals(3.5f, stats.averageUploadMbps, 0.001f)
    }

}
