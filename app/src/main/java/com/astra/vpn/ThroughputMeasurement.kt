package com.astra.vpn

/**
 * Validates a bounded download sample before it can influence server selection.
 * Incomplete transfers and implausibly short/long samples are never scored.
 */
internal object ThroughputMeasurement {
    const val DEFAULT_MIN_BYTES = 512L * 1024L
    const val DEFAULT_MAX_BYTES = 16L * 1024L * 1024L
    const val MIN_DURATION_NANOS = 10_000_000L
    const val MAX_DURATION_NANOS = 60_000_000_000L

    data class Sample(
        val bytes: Long,
        val durationNanos: Long,
        val downloadMbps: Double
    )

    fun fromTransfer(
        bytesRead: Long,
        expectedBytes: Long,
        durationNanos: Long,
        minBytes: Long = DEFAULT_MIN_BYTES,
        maxBytes: Long = DEFAULT_MAX_BYTES
    ): Sample? {
        if (minBytes <= 0L || maxBytes < minBytes) return null
        if (expectedBytes !in minBytes..maxBytes) return null
        if (bytesRead != expectedBytes) return null
        if (durationNanos !in MIN_DURATION_NANOS..MAX_DURATION_NANOS) return null
        val mbps = bytesRead.toDouble() * 8_000.0 / durationNanos.toDouble()
        if (!mbps.isFinite() || mbps <= 0.0) return null
        return Sample(bytesRead, durationNanos, mbps)
    }
}
