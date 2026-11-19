package com.astra.vpn

/**
 * Produces a robust recent-throughput value from completed download samples.
 * Only fresh, finite, positive measurements are considered; the median limits
 * the influence of one unusually fast or slow transfer.
 */
internal object ThroughputStabilityScorer {
    data class Sample(val timestampMs: Long, val downloadMbps: Double)

    fun medianMbps(
        samples: List<Sample>,
        nowMs: Long = System.currentTimeMillis(),
        windowMs: Long = DEFAULT_WINDOW_MS
    ): Double? {
        if (nowMs <= 0L || windowMs <= 0L) return null
        val fresh = samples.asSequence()
            .filter { it.timestampMs > 0L && it.timestampMs <= nowMs }
            .filter { nowMs - it.timestampMs <= windowMs }
            .map { it.downloadMbps }
            .filter { it.isFinite() && it > 0.0 }
            .sorted()
            .toList()
        if (fresh.isEmpty()) return null
        val middle = fresh.size / 2
        return if (fresh.size % 2 == 1) {
            fresh[middle]
        } else {
            (fresh[middle - 1] + fresh[middle]) / 2.0
        }
    }

    const val DEFAULT_WINDOW_MS = 24L * 60L * 60L * 1000L
}
