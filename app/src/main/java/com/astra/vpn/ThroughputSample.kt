package com.astra.vpn

import kotlin.math.ln

/**
 * A measured per-profile throughput sample. Values are accepted only when both
 * directions are finite and positive; timestamps are wall-clock milliseconds.
 */
internal data class ThroughputSample(
    val downloadMbps: Double,
    val uploadMbps: Double,
    val measuredAtEpochMs: Long
)

internal object ThroughputFreshnessPolicy {
    const val MAX_AGE_MS = 5 * 60 * 1000L
    const val MAX_FUTURE_SKEW_MS = 30_000L

    fun isFresh(sample: ThroughputSample, nowEpochMs: Long): Boolean {
        if (nowEpochMs <= 0L) return false
        if (!sample.downloadMbps.isUsableSpeed() || !sample.uploadMbps.isUsableSpeed()) return false
        if (sample.measuredAtEpochMs <= 0L) return false
        // Both timestamps are positive, so subtraction cannot overflow.
        val age = nowEpochMs - sample.measuredAtEpochMs
        return age >= -MAX_FUTURE_SKEW_MS && age <= MAX_AGE_MS
    }

    /**
     * Converts throughput into a deliberately small latency-score bonus.
     * The bonus decays linearly with sample age: a nearly expired speed test
     * must not influence selection as much as a freshly measured one.
     */
    fun selectionBonusMs(sample: ThroughputSample, nowEpochMs: Long): Long {
        if (!isFresh(sample, nowEpochMs)) return 0L
        val averageLogSpeed = (ln(1.0 + sample.downloadMbps) + ln(1.0 + sample.uploadMbps)) / 2.0
        val normalized = (averageLogSpeed / ln(101.0)).coerceIn(0.0, 1.0)
        val age = (nowEpochMs - sample.measuredAtEpochMs).coerceAtLeast(0L)
        val freshness = (MAX_AGE_MS - age).toDouble() / MAX_AGE_MS.toDouble()
        return (normalized * freshness * MAX_SELECTION_BONUS_MS).toLong()
    }

    private fun Double.isUsableSpeed(): Boolean = isFinite() && this > 0.0

    const val MAX_SELECTION_BONUS_MS = 20L
}
