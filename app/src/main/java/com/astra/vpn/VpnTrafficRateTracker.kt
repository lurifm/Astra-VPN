package com.astra.vpn

/**
 * Calculates throughput from cumulative sing-box proxy-outbound byte counters.
 * Counters must come from the same outbound tag and service lifetime.
 */
internal class VpnTrafficRateTracker(
    private val staleAfterMs: Long = 3_000L,
    private val maxSampleGapMs: Long = 5_000L
) {
    data class Rate(
        val downloadMbps: Double,
        val uploadMbps: Double,
        val sampleAgeMs: Long
    )

    private var previousDownloadBytes: Long? = null
    private var previousUploadBytes: Long? = null
    private var previousAtElapsedMs: Long? = null
    private var latestRate: Rate? = null

    init {
        require(staleAfterMs >= 0L)
        require(maxSampleGapMs > 0L)
    }

    @Synchronized
    fun update(downloadBytes: Long, uploadBytes: Long, nowElapsedMs: Long): Rate? {
        if (downloadBytes < 0L || uploadBytes < 0L || nowElapsedMs <= 0L) {
            reset()
            return null
        }

        val oldDownload = previousDownloadBytes
        val oldUpload = previousUploadBytes
        val oldAt = previousAtElapsedMs
        if (oldDownload == null || oldUpload == null || oldAt == null) {
            setBaseline(downloadBytes, uploadBytes, nowElapsedMs)
            return null
        }

        if (nowElapsedMs <= oldAt ||
            downloadBytes < oldDownload ||
            uploadBytes < oldUpload ||
            nowElapsedMs - oldAt > maxSampleGapMs
        ) {
            // Reconnect, process pause or clock anomaly: establish a fresh baseline.
            setBaseline(downloadBytes, uploadBytes, nowElapsedMs)
            return null
        }

        val elapsedMs = nowElapsedMs - oldAt
        if (elapsedMs <= 0L) {
            setBaseline(downloadBytes, uploadBytes, nowElapsedMs)
            return null
        }

        val downloadDelta = downloadBytes - oldDownload
        val uploadDelta = uploadBytes - oldUpload
        val seconds = elapsedMs / 1_000.0
        val rate = Rate(
            downloadMbps = downloadDelta * 8.0 / seconds / 1_000_000.0,
            uploadMbps = uploadDelta * 8.0 / seconds / 1_000_000.0,
            sampleAgeMs = 0L
        )
        setBaseline(downloadBytes, uploadBytes, nowElapsedMs)
        latestRate = rate
        return rate
    }

    @Synchronized
    fun current(nowElapsedMs: Long): Rate? {
        val sample = latestRate ?: return null
        val sampledAt = previousAtElapsedMs ?: return null
        val age = (nowElapsedMs - sampledAt).coerceAtLeast(0L)
        return if (age > staleAfterMs) {
            Rate(0.0, 0.0, age)
        } else {
            sample.copy(sampleAgeMs = age)
        }
    }

    @Synchronized
    fun reset() {
        previousDownloadBytes = null
        previousUploadBytes = null
        previousAtElapsedMs = null
        latestRate = null
    }

    private fun setBaseline(downloadBytes: Long, uploadBytes: Long, nowElapsedMs: Long) {
        previousDownloadBytes = downloadBytes
        previousUploadBytes = uploadBytes
        previousAtElapsedMs = nowElapsedMs
        latestRate = null
    }
}
