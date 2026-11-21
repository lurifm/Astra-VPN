package com.astra.vpn

/**
 * Selects reachable servers using measured latency, recent failures, stability
 * and (when fresh) a small per-profile throughput bonus. Live latency remains
 * the dominant signal; throughput can only refine close candidates.
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
        throughputSamples: Map<String, ThroughputSample> = emptyMap(),
        nowEpochMs: Long = System.currentTimeMillis()
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
                val baseScore = addPenalty(
                    addPenalty(addPenalty(latency, protocolPenalty), stabilityPenalty),
                    jitterPenalty
                )
                val throughputBonus = throughputSamples[profile]?.let {
                    ThroughputFreshnessPolicy.selectionBonusMs(it, nowEpochMs)
                } ?: 0L
                val score = (baseScore - throughputBonus).coerceAtLeast(1L)
                Candidate(profile, latency, score)
            }
            .minWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.latency })
            ?.profile
    }

    private data class Candidate(val profile: String, val latency: Long, val score: Long)

    /** Keep an extreme/corrupt latency sample from wrapping the score negative. */
    private fun addPenalty(latency: Long, penalty: Long): Long =
        if (Long.MAX_VALUE - latency < penalty) Long.MAX_VALUE else latency + penalty
}
