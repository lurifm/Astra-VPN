package com.astra.vpn

/**
 * Validates byte/time samples and converts them to Mbps.
 *
 * This is deliberately separate from device-wide speed monitoring: callers
 * must supply bytes transferred through one specifically selected proxy.
 */
internal object ThroughputMeasurement {
    const val MIN_SAMPLE_DURATION_MS = 250L
    const val REFERENCE_MBPS = 100.0
    const val MAX_SCORE_PENALTY_MS = 200L

    fun calculateMbps(bytesTransferred: Long, durationMs: Long): Double? {
        if (bytesTransferred <= 0L || durationMs < MIN_SAMPLE_DURATION_MS) return null
        return (bytesTransferred.toDouble() * 8.0) / (durationMs.toDouble() * 1000.0)
            .takeIf { it.isFinite() && it > 0.0 }
    }

    /**
     * Converts throughput to a capped latency-equivalent penalty.
     * 100 Mbps or faster adds no penalty; lower throughput is progressively
     * penalized, while missing/invalid measurements leave latency scoring intact.
     */
    fun scorePenaltyMs(throughputMbps: Double?): Long {
        val speed = throughputMbps?.takeIf { it.isFinite() && it > 0.0 } ?: return 0L
        val penalty = ((REFERENCE_MBPS / speed) - 1.0).coerceAtLeast(0.0) * 20.0
        return penalty.toLong().coerceIn(0L, MAX_SCORE_PENALTY_MS)
    }
}
