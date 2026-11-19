package com.astra.vpn

/**
 * Selects reachable servers using measured latency, optional real throughput,
 * and recent recorded failures. Throughput is a bounded secondary signal:
 * it can break close latency results but cannot hide a severely slower ping.
 */
internal object BestServerSelector {
    private const val UDP_PREFERENCE_MS = 12L
    private const val FAILURE_PENALTY_MS = 18L
    private const val MAX_FAILURE_PENALTY_MS = 90L

    fun select(
        profiles: List<String>,
        latencyMs: Map<String, Long>,
        recentFailures: Map<String, Int> = emptyMap(),
        latencyStabilityPenaltyMs: Map<String, Long> = emptyMap(),
        throughputMbps: Map<String, Double> = emptyMap()
    ): String? {
        return profiles.asSequence()
            .mapNotNull { profile ->
                val latency = latencyMs[profile]?.takeIf { it > 0L } ?: return@mapNotNull null
                val normalized = profile.trim().lowercase()
                val protocolPenalty = when {
                    normalized.startsWith("hysteria2://") || normalized.startsWith("hy2://") ||
                        normalized.startsWith("tuic://") -> 0L
                    normalized.startsWith("vless://") -> UDP_PREFERENCE_MS
                    else -> UDP_PREFERENCE_MS / 2L
                }
                val failures = recentFailures[profile]?.coerceAtLeast(0) ?: 0
                val stabilityPenalty = (failures.toLong() * FAILURE_PENALTY_MS)
                    .coerceAtMost(MAX_FAILURE_PENALTY_MS)
                val jitterPenalty = (latencyStabilityPenaltyMs[profile] ?: 0L)
                    .coerceIn(0L, LatencyStabilityScorer.MAX_PENALTY_MS)
                val throughputPenalty = ThroughputMeasurement.scorePenaltyMs(throughputMbps[profile])
                val score = addPenalty(
                    addPenalty(
                        addPenalty(addPenalty(latency, protocolPenalty), stabilityPenalty),
                        jitterPenalty
                    ),
                    throughputPenalty
                )
                Triple(profile, latency, score)
            }
            .minWithOrNull(compareBy<Triple<String, Long, Long>> { it.third }.thenBy { it.second })
            ?.first
    }

    /** Keep an extreme/corrupt latency sample from wrapping the score negative. */
    private fun addPenalty(latency: Long, penalty: Long): Long =
        if (Long.MAX_VALUE - latency < penalty) Long.MAX_VALUE else latency + penalty
}
