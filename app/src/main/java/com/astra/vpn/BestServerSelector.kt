package com.astra.vpn

/**
 * Selects only servers with a successful URL-test result.
 *
 * Hysteria2/TUIC receive a small tie-break preference because they use UDP.
 * The preference is deliberately capped: a materially faster VLESS server
 * still wins. URL-test latency remains the primary signal; this is not a
 * substitute for historical stability or throughput measurements.
 */
internal object BestServerSelector {
    private const val UDP_PREFERENCE_MS = 12L

    fun select(profiles: List<String>, latencyMs: Map<String, Long>): String? {
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
                Triple(profile, latency, latency + protocolPenalty)
            }
            .minWithOrNull(compareBy<Triple<String, Long, Long>> { it.third }.thenBy { it.second })
            ?.first
    }
}
