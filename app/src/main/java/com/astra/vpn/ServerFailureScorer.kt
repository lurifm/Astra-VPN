package com.astra.vpn

import java.util.Locale

/**
 * Converts recent connection errors into a recency-weighted reliability signal.
 *
 * Errors in the last six hours count 3x, errors in the last day count 2x,
 * and older errors in the seven-day window count 1x. Future/corrupt timestamps
 * and non-error state transitions are ignored. The output is deliberately
 * bounded to Int.MAX_VALUE because BestServerSelector accepts integer penalties.
 */
internal object ServerFailureScorer {
    const val DEFAULT_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val RECENT_WINDOW_MS = 6L * 60L * 60L * 1_000L
    private const val DAY_MS = 24L * 60L * 60L * 1_000L

    fun weightedFailures(
        entries: List<ConnectionHistoryStore.Entry>,
        now: Long,
        windowMs: Long = DEFAULT_WINDOW_MS
    ): Map<String, Int> {
        if (now < 0L || windowMs <= 0L) return emptyMap()
        val totals = linkedMapOf<String, Int>()
        entries.forEach { entry ->
            if (entry.state != "error" || entry.timestamp < 0L || entry.timestamp > now) return@forEach
            val age = now - entry.timestamp
            if (age > windowMs) return@forEach
            val name = entry.serverName.trim().lowercase(Locale.ROOT)
            if (name.isEmpty()) return@forEach
            val weight = when {
                age <= RECENT_WINDOW_MS -> 3
                age <= DAY_MS -> 2
                else -> 1
            }
            val current = totals[name] ?: 0
            totals[name] = if (Int.MAX_VALUE - current < weight) Int.MAX_VALUE else current + weight
        }
        return totals
    }
}
