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
        val candidates = profiles.mapNotNull { profile ->
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
            Candidate(profile, latency, baseScore, throughputBonus)
        }
        if (candidates.isEmpty()) return null

        // The bonus is capped at 20 ms, so throughput can only overcome
        // a similarly small base-score gap. Do not clamp adjusted scores to
        // the best base score: doing so would let a much worse candidate tie
        // and win merely because it has a larger throughput bonus.
        return candidates.minWithOrNull(
            compareBy<Candidate> {
                // Subtract without overflow: a saturated Long.MAX_VALUE base score
                // must never wrap negative and become the best possible candidate.
                if (it.baseScore > it.throughputBonus) {
                    it.baseScore - it.throughputBonus
                } else {
                    1L
                }
            }
                .thenBy { it.baseScore }
                .thenByDescending { it.throughputBonus }
                .thenBy { it.latency }
        )?.profile
    }

    private data class Candidate(
        val profile: String,
        val latency: Long,
        val baseScore: Long,
        val throughputBonus: Long
    )

    /** Keep an extreme/corrupt latency sample from wrapping the score negative. */
    private fun addPenalty(latency: Long, penalty: Long): Long =
        if (Long.MAX_VALUE - latency < penalty) Long.MAX_VALUE else latency + penalty
}
