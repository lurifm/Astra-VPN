package com.astra.vpn

/** Prevents automatic selection from relying on stale measurements. */
internal object ServerSelectionFreshness {
    const val MAX_LATENCY_AGE_MS = 10 * 60_000L
    const val MAX_THROUGHPUT_AGE_MS = 60 * 60_000L

    fun isFresh(timestamp: Long, now: Long, maxAgeMs: Long): Boolean =
        timestamp > 0L && now >= timestamp && maxAgeMs >= 0L && now - timestamp <= maxAgeMs

    fun usableLatency(timestamp: Long, now: Long): Boolean =
        isFresh(timestamp, now, MAX_LATENCY_AGE_MS)

    fun usableThroughput(timestamp: Long, now: Long): Boolean =
        isFresh(timestamp, now, MAX_THROUGHPUT_AGE_MS)
}
