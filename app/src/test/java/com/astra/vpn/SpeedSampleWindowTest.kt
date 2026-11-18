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
}
