package com.astra.vpn

/** A transparent heuristic, not a provider uptime measurement. */
internal object ReliabilityScore {
    data class Result(val score: Int, val sessions: Int, val failures: Int, val confidence: String)

    fun calculate(eventsNewestFirst: List<ConnectionHistoryStore.Entry>): Result? {
        // Ignore malformed and unrelated state records before applying the sample cap,
        // so they cannot hide valid connection outcomes from the reliability estimate.
        val relevant = eventsNewestFirst.asSequence()
            .filter { it.timestamp > 0L && (it.state == "connected" || it.state == "error") }
            .take(MAX_EVENTS)
            .toList()
        val sessions = relevant.count { it.state == "connected" }
        val failures = relevant.count { it.state == "error" }
        val total = sessions + failures
        if (total < MIN_EVENTS) return null

        // Use the observed success ratio rather than subtracting a fixed number
        // of points per error; the same error count means different things for
        // a profile with 3 attempts versus one with 30 successful sessions.
        val score = ((sessions * 100) + total / 2) / total
        val confidence = when {
            total >= HIGH_CONFIDENCE_EVENTS -> "высокая"
            total >= MEDIUM_CONFIDENCE_EVENTS -> "средняя"
            else -> "предварительная"
        }
        return Result(score.coerceIn(0, 100), sessions, failures, confidence)
    }

    /**
     * A validated recovery supersedes earlier errors for the same profile.
     * Keep successful outcomes and only failures that happened after the
     * latest success, so old incidents do not permanently poison the score.
     */
    fun outcomesAfterLatestSuccess(
        eventsNewestFirst: List<ConnectionHistoryStore.Entry>
    ): List<ConnectionHistoryStore.Entry> {
        val latestSuccess = eventsNewestFirst.asSequence()
            .filter { it.timestamp > 0L && it.state == "connected" }
            .maxOfOrNull { it.timestamp } ?: 0L
        if (latestSuccess == 0L) return eventsNewestFirst
        return eventsNewestFirst.filter {
            (it.state == "connected" && it.timestamp == latestSuccess) ||
                (it.state == "error" && it.timestamp > latestSuccess)
        }
    }

    /**
     * Convert the observed success ratio into a conservative selector penalty.
     * Early samples have low confidence, so their influence ramps up gradually
     * instead of treating three outcomes like a mature reliability record.
     */
    fun selectionPenaltyMs(result: Result, maxPenaltyMs: Long = 60L): Long {
        if (maxPenaltyMs <= 0L) return 0L
        val total = (result.sessions + result.failures).coerceAtLeast(0)
        if (total < MIN_EVENTS) return 0L
        val confidence = total.coerceAtMost(HIGH_CONFIDENCE_EVENTS).toLong()
        val confidenceScale = confidence * 100L / HIGH_CONFIDENCE_EVENTS
        val failureRate = (100 - result.score).coerceIn(0, 100).toLong()
        return (maxPenaltyMs.coerceAtMost(60L) * failureRate * confidenceScale) / 10_000L
    }

    /**
     * Build all per-server penalties in one pass over history. The key format
     * matches short-term failure scoring and canonicalizes the Hy2 alias.
     */
    fun selectionPenaltiesByIdentity(
        entries: List<ConnectionHistoryStore.Entry>
    ): Map<String, Long> {
        val grouped = HashMap<String, MutableList<ConnectionHistoryStore.Entry>>()
        entries.forEach { entry ->
            if (entry.timestamp <= 0L || (entry.state != "connected" && entry.state != "error")) return@forEach
            val identity = identity(entry.serverName, entry.protocol)
            grouped.getOrPut(identity) { ArrayList() }.add(entry)
        }
        return grouped.mapNotNull { (identity, outcomes) ->
            val result = calculate(outcomesAfterLatestSuccess(outcomes)) ?: return@mapNotNull null
            val penalty = selectionPenaltyMs(result)
            if (penalty > 0L) identity to penalty else null
        }.toMap()
    }

    fun identity(serverName: String, protocol: String): String {
        val normalizedName = serverName.trim().lowercase(java.util.Locale.ROOT)
        val normalizedProtocol = protocol.trim().lowercase(java.util.Locale.ROOT)
            .let { if (it == "hy2") "hysteria2" else it }
        return "$normalizedName|$normalizedProtocol"
    }

    private const val MAX_EVENTS = 100
    private const val MIN_EVENTS = 3
    private const val MEDIUM_CONFIDENCE_EVENTS = 8
    private const val HIGH_CONFIDENCE_EVENTS = 20
}
