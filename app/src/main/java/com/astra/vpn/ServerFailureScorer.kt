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
        if (now <= 0L || windowMs <= 0L) return emptyMap()
        val latestSuccessByIdentity = latestSuccessTimestamps(entries, now)
        val totals = linkedMapOf<String, Int>()
        entries.forEach { entry ->
            if (entry.state != "error" || entry.timestamp <= 0L || entry.timestamp > now) return@forEach
            val age = now - entry.timestamp
            if (age > windowMs) return@forEach
            val name = normalizeIdentity(entry.serverName)
            val protocol = normalizeProtocol(entry.protocol)
            if (name.isEmpty() || protocol.isEmpty()) return@forEach
            val key = "$name|$protocol"
            // A later end-to-end validated session proves recovery. Only errors
            // newer than that success should influence the next server decision.
            if (entry.timestamp <= (latestSuccessByIdentity[key] ?: 0L)) return@forEach
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
        protocolOf: (String) -> String,
        escalationWindowMs: Long = 60L * 60L * 1_000L,
        maxQuarantineMs: Long = 30L * 60L * 1_000L
    ): List<String> {
        if (profiles.isEmpty() || now <= 0L || quarantineMs <= 0L ||
            escalationWindowMs <= 0L || maxQuarantineMs <= 0L
        ) return profiles

        // Repeated failures extend only that server's quarantine. A single
        // transient failure keeps the original five-minute recovery window.
        val latestSuccessByIdentity = latestSuccessTimestamps(entries, now)
        val recentErrors = entries.asSequence()
            .filter { it.state == "error" && it.timestamp > 0L && it.timestamp <= now }
            .filter { now - it.timestamp <= escalationWindowMs }
            .mapNotNull { entry ->
                val name = normalizeIdentity(entry.serverName)
                val protocol = normalizeProtocol(entry.protocol)
                if (name.isBlank() || protocol.isBlank()) null
                else {
                    val key = "$name|$protocol"
                    if (entry.timestamp <= (latestSuccessByIdentity[key] ?: 0L)) null
                    else key to entry.timestamp
                }
            }
            .groupBy({ it.first }, { it.second })

        val effectiveMax = maxQuarantineMs.coerceAtLeast(quarantineMs)
        val quarantined = recentErrors.mapNotNull { (identity, timestamps) ->
            val latestFailure = timestamps.maxOrNull() ?: return@mapNotNull null
            var duration = quarantineMs
            repeat((timestamps.size - 1).coerceAtMost(30)) {
                duration = if (duration >= effectiveMax / 2L) effectiveMax
                else (duration * 2L).coerceAtMost(effectiveMax)
            }
            if (now - latestFailure <= duration) identity else null
        }.toSet()
        if (quarantined.isEmpty()) return profiles
        val preferred = profiles.filter { profile ->
            val key = "${normalizeIdentity(nameOf(profile))}|${normalizeProtocol(protocolOf(profile))}"
            key !in quarantined
        }
        return preferred.ifEmpty { profiles }
    }

    private fun latestSuccessTimestamps(
        entries: List<ConnectionHistoryStore.Entry>,
        now: Long
    ): Map<String, Long> = entries.asSequence()
        .filter { it.state == "connected" && it.timestamp > 0L && it.timestamp <= now }
        .mapNotNull { entry ->
            val name = normalizeIdentity(entry.serverName)
            val protocol = normalizeProtocol(entry.protocol)
            if (name.isBlank() || protocol.isBlank()) null
            else "$name|$protocol" to entry.timestamp
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, timestamps) -> timestamps.maxOrNull() ?: 0L }

    private fun normalizeIdentity(value: String): String =
        value.trim().lowercase(Locale.ROOT)

    private fun normalizeProtocol(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return if (normalized == "hy2") "hysteria2" else normalized
    }
}
