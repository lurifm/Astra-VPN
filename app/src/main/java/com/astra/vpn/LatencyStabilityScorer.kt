package com.astra.vpn

/**
 * Converts recent URL-test samples into a bounded latency-jitter penalty.
 * Median absolute deviation is used instead of standard deviation so one
 * isolated spike does not dominate the server score.
 */
internal object LatencyStabilityScorer {
    const val DEFAULT_WINDOW_MS = 72L * 60L * 60L * 1_000L
    const val MAX_PENALTY_MS = 120L
    private const val MIN_SAMPLES = 3

    data class Sample(val timestampMs: Long, val latencyMs: Long)

    fun penaltyMs(
        samples: List<Sample>,
        now: Long,
        windowMs: Long = DEFAULT_WINDOW_MS
    ): Long {
        if (now <= 0L || windowMs <= 0L) return 0L
        val values = samples.asSequence()
            .filter { sample ->
                sample.timestampMs > 0L &&
                    sample.timestampMs <= now &&
                    now - sample.timestampMs <= windowMs &&
                    sample.latencyMs > 0L
            }
            .map { it.latencyMs }
            .sorted()
            .toList()
        if (values.size < MIN_SAMPLES) return 0L

        val center = median(values)
        val deviations = values.map { value ->
            if (value >= center) value - center else center - value
        }.sorted()
        return median(deviations).coerceAtMost(MAX_PENALTY_MS)
    }

    private fun median(sortedValues: List<Long>): Long {
        val middle = sortedValues.size / 2
        if (sortedValues.size % 2 == 1) return sortedValues[middle]
        val lower = sortedValues[middle - 1]
        val upper = sortedValues[middle]
        return lower + (upper - lower) / 2L
    }
}
