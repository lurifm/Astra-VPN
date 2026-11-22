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
    private const val MIN_TREND_SAMPLES = 3
    private const val RECENT_WINDOW_MS = 30L * 60L * 1_000L
    private const val MIN_DEGRADATION_MS = 15L

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
        val jitterPenalty = median(deviations)
        val degradationPenalty = sustainedDegradationPenalty(samples, now, windowMs)
        // A sustained recent slowdown matters even when the historical series
        // is internally stable. A single spike is not enough to trigger it.
        return maxOf(jitterPenalty, degradationPenalty).coerceAtMost(MAX_PENALTY_MS)
    }

    private fun sustainedDegradationPenalty(
        samples: List<Sample>,
        now: Long,
        windowMs: Long
    ): Long {
        val recent = samples.asSequence()
            .filter { sample ->
                sample.timestampMs > 0L &&
                    sample.timestampMs <= now &&
                    now - sample.timestampMs <= RECENT_WINDOW_MS &&
                    sample.latencyMs > 0L
            }
            .map { it.latencyMs }
            .sorted()
            .toList()
        if (recent.size < MIN_TREND_SAMPLES) return 0L

        val baseline = samples.asSequence()
            .filter { sample ->
                sample.timestampMs > 0L &&
                    sample.timestampMs <= now &&
                    now - sample.timestampMs > RECENT_WINDOW_MS &&
                    now - sample.timestampMs <= windowMs &&
                    sample.latencyMs > 0L
            }
            .map { it.latencyMs }
            .sorted()
            .toList()
        if (baseline.size < MIN_TREND_SAMPLES) return 0L

        val recentMedian = median(recent)
        val baselineMedian = median(baseline)
        if (recentMedian <= baselineMedian) return 0L
        val increase = recentMedian - baselineMedian
        // Ignore small natural variation; penalize only a material regression.
        if (increase < maxOf(MIN_DEGRADATION_MS, baselineMedian / 5L)) return 0L
        return increase.coerceAtMost(MAX_PENALTY_MS)
    }

    private fun median(sortedValues: List<Long>): Long {
        val middle = sortedValues.size / 2
        if (sortedValues.size % 2 == 1) return sortedValues[middle]
        val lower = sortedValues[middle - 1]
        val upper = sortedValues[middle]
        return lower + (upper - lower) / 2L
    }
}
