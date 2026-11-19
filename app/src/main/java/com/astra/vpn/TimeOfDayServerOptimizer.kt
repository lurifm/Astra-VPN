package com.astra.vpn

import java.util.Calendar
import java.util.TimeZone

/** Blends current URL-test latency with same-time-of-day history when enough samples exist. */
internal object TimeOfDayServerOptimizer {
    fun adjustedLatencies(
        profiles: List<String>,
        currentLatencies: Map<String, Long>,
        samples: (String) -> List<LatencyStabilityScorer.Sample>,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Map<String, Long> {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = now }
        val weekday = calendar.get(Calendar.DAY_OF_WEEK)
        val hourBucket = calendar.get(Calendar.HOUR_OF_DAY) / 3
        return profiles.mapNotNull { profile ->
            val current = currentLatencies[profile]?.takeIf { it > 0L } ?: return@mapNotNull null
            val matching = samples(profile).filter { sample ->
                if (sample.timestampMs <= 0L || sample.timestampMs > now) return@filter false
                val sampleCalendar = Calendar.getInstance(timeZone).apply { timeInMillis = sample.timestampMs }
                sampleCalendar.get(Calendar.DAY_OF_WEEK) == weekday &&
                    sampleCalendar.get(Calendar.HOUR_OF_DAY) / 3 == hourBucket
            }.takeLast(6)
            val adjusted = if (matching.size >= 2) {
                val historical = matching.map { it.latencyMs }.average().toLong().coerceAtLeast(1L)
                // Current measurement remains dominant; history only smooths time-specific variation.
                (current * 0.7 + historical * 0.3).toLong().coerceAtLeast(1L)
            } else current
            profile to adjusted
        }.toMap()
    }
}
