package com.astra.vpn

import java.util.ArrayDeque

/** Rolling one-minute window for device-wide throughput samples (Mbps). */
internal class SpeedSampleWindow(private val capacity: Int = 60) {
    data class Sample(val downloadMbps: Float, val uploadMbps: Float)
    data class Stats(
        val averageDownloadMbps: Float,
        val averageUploadMbps: Float,
        val peakDownloadMbps: Float,
        val peakUploadMbps: Float,
        val sampleCount: Int
    )

    private val samples = ArrayDeque<Sample>()

    init { require(capacity > 0) }

    fun add(downloadMbps: Float, uploadMbps: Float) {
        // A malformed counter conversion must not poison the rolling statistics
        // with NaN or infinity; keep the window usable and deterministic.
        val safeDownload = if (downloadMbps.isFinite()) downloadMbps.coerceAtLeast(0f) else 0f
        val safeUpload = if (uploadMbps.isFinite()) uploadMbps.coerceAtLeast(0f) else 0f
        samples.addLast(Sample(safeDownload, safeUpload))
        while (samples.size > capacity) samples.removeFirst()
    }

    fun stats(): Stats {
        if (samples.isEmpty()) return Stats(0f, 0f, 0f, 0f, 0)
        val count = samples.size.toFloat()
        return Stats(
            averageDownloadMbps = samples.sumOf { it.downloadMbps.toDouble() }.toFloat() / count,
            averageUploadMbps = samples.sumOf { it.uploadMbps.toDouble() }.toFloat() / count,
            peakDownloadMbps = samples.maxOf { it.downloadMbps },
            peakUploadMbps = samples.maxOf { it.uploadMbps },
            sampleCount = samples.size
        )
    }
}
