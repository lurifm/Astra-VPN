package com.astra.vpn

/**
 * Limits optional throughput probes to the best latency candidates.
 * This keeps a large subscription from causing excessive background data use
 * or making the "Fast" action wait for dozens of sequential downloads.
 */
internal object ThroughputCandidateSelector {
    const val DEFAULT_MAX_CANDIDATES = 5

    fun select(
        profiles: List<String>,
        latencyMs: Map<String, Long>,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES
    ): List<String> {
        if (maxCandidates <= 0) return emptyList()
        return profiles.asSequence()
            .distinct()
            .mapNotNull { profile ->
                val latency = latencyMs[profile]
                if (profile.isBlank() || latency == null || latency <= 0L) null
                else profile to latency
            }
            .sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })
            .take(maxCandidates)
            .map { it.first }
            .toList()
    }
}
