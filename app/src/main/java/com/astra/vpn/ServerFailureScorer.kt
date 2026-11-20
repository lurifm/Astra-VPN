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
            val name = normalizeIdentity(entry.serverName)
            val protocol = normalizeProtocol(entry.protocol)
            if (name.isEmpty() || protocol.isEmpty()) return@forEach
            val key = "$name|$protocol"
            val weight = when {
                age <= RECENT_WINDOW_MS -> 3
                age <= DAY_MS -> 2
                else -> 1
            }
            val current = totals[key] ?: 0
            totals[key] = if (Int.MAX_VALUE - current < weight) Int.MAX_VALUE else current + weight
        }
        return totals
    }

    /**
     * Temporarily avoid a profile that just failed, so repeated failovers do not
     * bounce straight back to the previous broken endpoint. If every candidate
     * is quarantined, keep the original list: a last-resort connection is better
     * than making automatic recovery impossible.
     */
    fun excludeRecentlyFailedProfiles(
        profiles: List<String>,
        entries: List<ConnectionHistoryStore.Entry>,
        now: Long,
        quarantineMs: Long = 5L * 60L * 1_000L,
        nameOf: (String) -> String,
        protocolOf: (String) -> String
    ): List<String> {
        if (profiles.isEmpty() || now < 0L || quarantineMs <= 0L) return profiles
        val quarantined = entries.asSequence()
            .filter { it.state == "error" && it.timestamp >= 0L && it.timestamp <= now }
            .filter { now - it.timestamp <= quarantineMs }
            .mapNotNull { entry ->
                val name = normalizeIdentity(entry.serverName)
                val protocol = normalizeProtocol(entry.protocol)
                if (name.isBlank() || protocol.isBlank()) null else "$name|$protocol"
            }
            .toSet()
        if (quarantined.isEmpty()) return profiles
        val preferred = profiles.filter { profile ->
            val key = "${normalizeIdentity(nameOf(profile))}|${normalizeProtocol(protocolOf(profile))}"
            key !in quarantined
        }
        return preferred.ifEmpty { profiles }
    }

    private fun normalizeIdentity(value: String): String =
        value.trim().lowercase(Locale.ROOT)

    private fun normalizeProtocol(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return if (normalized == "hy2") "hysteria2" else normalized
    }
}
