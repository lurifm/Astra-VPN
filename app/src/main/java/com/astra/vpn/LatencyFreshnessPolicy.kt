package com.astra.vpn

/**
 * Keeps automatic server switching tied to measurements made recently in the
 * current process. Historical samples may influence ranking, but cannot make
 * an unmeasured/stale server eligible for an immediate failover.
 */
internal object LatencyFreshnessPolicy {
    const val DEFAULT_MAX_AGE_MS = 5L * 60L * 1_000L

    fun freshLatencies(
        latencies: Map<String, Long>,
        measuredAtElapsedMs: Map<String, Long>,
        nowElapsedMs: Long,
        candidates: Collection<String>,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS
    ): Map<String, Long> {
        if (nowElapsedMs <= 0L || maxAgeMs <= 0L) return emptyMap()
        return candidates.mapNotNull { profile ->
            val latency = latencies[profile]?.takeIf { it > 0L } ?: return@mapNotNull null
            val measuredAt = measuredAtElapsedMs[profile]?.takeIf { it > 0L } ?: return@mapNotNull null
            if (measuredAt > nowElapsedMs || nowElapsedMs - measuredAt > maxAgeMs) {
                return@mapNotNull null
            }
            profile to latency
        }.toMap()
    }
}
