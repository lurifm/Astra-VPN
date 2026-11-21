package com.astra.vpn

/**
 * Chooses a bounded set of servers for the short throughput phase of "Прыжок".
 * Candidates are ordered by successful latency, and already-fresh throughput
 * samples are reused instead of spending mobile data on duplicate probes.
 */
internal object ThroughputBatchPlanner {
    const val DEFAULT_LIMIT = 5

    fun plan(
        profiles: List<String>,
        latencyMs: Map<String, Long>,
        freshSamples: Map<String, ThroughputSample>,
        limit: Int = DEFAULT_LIMIT,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<String> {
        if (limit <= 0) return emptyList()
        // A subscription can contain the same URI more than once. Probe each
        // endpoint at most once per batch to avoid duplicate traffic.
        val seen = HashSet<String>()
        return profiles.withIndex()
            .filter { (_, profile) ->
                profile.isNotBlank() &&
                    seen.add(profile) &&
                    (latencyMs[profile] ?: 0L) > 0L &&
                    freshSamples[profile]?.let {
                        ThroughputFreshnessPolicy.isFresh(it, nowEpochMs)
                    } != true
            }
            .sortedWith(compareBy<IndexedValue<String>> { latencyMs[it.value] ?: Long.MAX_VALUE }
                .thenBy { it.index })
            .take(limit)
            .map { it.value }
    }
}
