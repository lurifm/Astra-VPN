package com.astra.vpn

/**
 * Selects reachable servers using measured latency, throughput and recent
 * stability history. Latency remains dominant; throughput is a bounded
 * secondary signal so a much slower ping cannot win on bandwidth alone.
 */
internal object BestServerSelector {
    private const val UDP_PREFERENCE_MS = 12L
    private const val FAILURE_PENALTY_MS = 18L
    private const val MAX_FAILURE_PENALTY_MS = 90L
    private const val MAX_THROUGHPUT_PENALTY_MS = 40L

    fun select(
        profiles: List<String>,
        latencyMs: Map<String, Long>,
        recentFailures: Map<String, Int> = emptyMap(),
        latencyStabilityPenaltyMs: Map<String, Long> = emptyMap(),
        throughputMbps: Map<String, Double> = emptyMap(),
        latencyTimestamps: Map<String, Long> = emptyMap(),
        throughputTimestamps: Map<String, Long> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
        localHour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
        preferredProtocols: Map<TimeOfDayServerPolicy.Window, Set<String>> = emptyMap()
    ): String? {
        // A stale high-speed sample must not distort the penalty applied to
        // fresh candidates. When timestamp metadata is supplied, only fresh
        // throughput samples participate in the reference maximum.
        val fastestSpeed = throughputMbps.entries
            .filter { (profile, speed) ->
                speed.isFinite() && speed > 0.0 &&
                    (throughputTimestamps.isEmpty() ||
                        throughputTimestamps[profile]?.let {
                            ServerSelectionFreshness.usableThroughput(it, nowMs)
                        } == true)
            }
            .maxOfOrNull { it.value }
        return profiles.asSequence()
            .mapNotNull { profile ->
                val latency = latencyMs[profile]?.takeIf { it > 0L } ?: return@mapNotNull null
                val latencyTimestamp = latencyTimestamps[profile]
                if (latencyTimestamps.isNotEmpty() &&
                    (latencyTimestamp == null || !ServerSelectionFreshness.usableLatency(latencyTimestamp, nowMs))
                ) return@mapNotNull null
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
                val speedTimestamp = throughputTimestamps[profile]
                val speedIsFresh = throughputTimestamps.isEmpty() ||
                    (speedTimestamp != null && ServerSelectionFreshness.usableThroughput(speedTimestamp, nowMs))
                val speed = throughputMbps[profile]?.takeIf { it.isFinite() && it > 0.0 && speedIsFresh }
                val speedPenalty = if (speed != null && fastestSpeed != null && fastestSpeed > 0.0) {
                    (((fastestSpeed - speed) / fastestSpeed) * MAX_THROUGHPUT_PENALTY_MS)
                        .toLong()
                        .coerceIn(0L, MAX_THROUGHPUT_PENALTY_MS)
                } else 0L
                val baseScore = addPenalty(
                    addPenalty(addPenalty(addPenalty(latency, protocolPenalty), stabilityPenalty), jitterPenalty),
                    speedPenalty
                )
                val timeBonus = TimeOfDayServerPolicy.preferredScoreBonus(profile, localHour, preferredProtocols)
                val score = (baseScore - timeBonus.toLong()).coerceAtLeast(0L)
                Triple(profile, latency, score)
            }
            .minWithOrNull(compareBy<Triple<String, Long, Long>> { it.third }.thenBy { it.second })
            ?.first
    }

    /** Keep an extreme/corrupt latency sample from wrapping the score negative. */
    private fun addPenalty(latency: Long, penalty: Long): Long =
        if (Long.MAX_VALUE - latency < penalty) Long.MAX_VALUE else latency + penalty
}
