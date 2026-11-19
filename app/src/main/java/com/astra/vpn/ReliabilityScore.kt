package com.astra.vpn

/** A transparent heuristic, not a provider uptime measurement. */
internal object ReliabilityScore {
    data class Result(val score: Int, val sessions: Int, val failures: Int, val confidence: String)

    fun calculate(eventsNewestFirst: List<ConnectionHistoryStore.Entry>): Result? {
        val relevant = eventsNewestFirst.take(100)
        val sessions = relevant.count { it.state == "connected" }
        val failures = relevant.count { it.state == "error" }
        if (sessions + failures < 3) return null
        val score = (100 - failures * 18).coerceIn(0, 100)
        val confidence = when {
            sessions + failures >= 20 -> "высокая"
            sessions + failures >= 8 -> "средняя"
            else -> "предварительная"
        }
        return Result(score, sessions, failures, confidence)
    }
}
