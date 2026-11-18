package com.astra.vpn

/**
 * Selects reachable servers using measured latency and recent recorded failures.
 * Historical penalties are capped and deliberately small; live latency remains
 * the dominant signal. The protocol preference is only a tie-breaker.
 */
internal object BestServerSelector {
    private const val UDP_PREFERENCE_MS = 12L
    private const val FAILURE_PENALTY_MS = 18L
    private const val MAX_FAILURE_PENALTY_MS = 90L

    fun select(
        profiles: List<String>,
        latencyMs: Map<String, Long>,
        recentFailures: Map<String, Int> = emptyMap()
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
                Triple(profile, latency, latency + protocolPenalty + stabilityPenalty)
            }
            .minWithOrNull(compareBy<Triple<String, Long, Long>> { it.third }.thenBy { it.second })
            ?.first
    }
}
