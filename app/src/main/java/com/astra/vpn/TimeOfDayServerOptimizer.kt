package com.astra.vpn

import java.util.Calendar
import java.util.TimeZone

/**
 * Blends current URL-test latency with same-time-of-day history when enough
 * independent samples exist. The latest sample may be the current test,
 * recorded immediately before selection, so a single previous point is insufficient.
 */
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
            val matching = samples(profile).asSequence().filter { sample ->
                if (sample.timestampMs <= 0L || sample.timestampMs > now || sample.latencyMs <= 0L) {
                    return@filter false
                }
                val sampleCalendar = Calendar.getInstance(timeZone).apply { timeInMillis = sample.timestampMs }
                sampleCalendar.get(Calendar.DAY_OF_WEEK) == weekday &&
                    sampleCalendar.get(Calendar.HOUR_OF_DAY) / 3 == hourBucket
            }.sortedBy { it.timestampMs }.takeLast(MAX_HISTORY_SAMPLES).toList()

            // Require three points: at most one is the current measurement just recorded by the caller.
            val adjusted = if (matching.size >= MIN_MATCHING_SAMPLES) {
                val historical = median(matching.map { it.latencyMs })
                // Current measurement remains dominant; history only smooths time-specific variation.
                (current * CURRENT_WEIGHT + historical * HISTORY_WEIGHT).toLong().coerceAtLeast(1L)
            } else current
            profile to adjusted
        }.toMap()
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else sorted[middle - 1] + (sorted[middle] - sorted[middle - 1]) / 2L
    }

    private const val MIN_MATCHING_SAMPLES = 3
    private const val MAX_HISTORY_SAMPLES = 6
    private const val CURRENT_WEIGHT = 0.7
    private const val HISTORY_WEIGHT = 0.3
}
