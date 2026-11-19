package com.astra.vpn

/** Deterministic 0..100 quality score from recent successful/failed checks. */
internal object ServerStabilityRating {
    fun score(latenciesMs: List<Long?>): Int? {
        if (latenciesMs.isEmpty()) return null
        val valid = latenciesMs.filterNotNull().filter { it > 0L }
        val successRatio = valid.size.toDouble() / latenciesMs.size
        if (valid.isEmpty()) return 0
        val mean = valid.average()
        val variance = valid.map { (it - mean) * (it - mean) }.average()
        val relativeDeviation = if (mean > 0.0) kotlin.math.sqrt(variance) / mean else 1.0
        val latencyFactor = (1.0 - (mean / 1_000.0).coerceIn(0.0, 1.0))
        val consistencyFactor = (1.0 - relativeDeviation.coerceIn(0.0, 1.0))
        return (100.0 * successRatio * (0.65 * latencyFactor + 0.35 * consistencyFactor))
            .toInt().coerceIn(0, 100)
    }
}
